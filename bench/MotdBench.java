import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 状态查询（MOTD）压测客户端，零第三方依赖，供 CI 性能对比任务使用。
 *
 * <p>完整状态流程：握手 → 状态请求 → 状态响应 → 心跳 → 心跳应答 → 等待服务端关闭。
 * 客户端始终等待服务端 FIN（被动关闭），避免 TIME_WAIT 耗尽本地端口。</p>
 *
 * 用法：
 * <pre>
 *   java MotdBench host port --latency 2000   预热后串行 N 次完整 ping，输出 "LATENCY_US 平均微秒"
 *   java MotdBench host port --bench 8 --threads 8
 *                                             并发压测 N 秒，输出 "BENCH_QPS 每秒次数 FAIL 失败数"
 * </pre>
 */
public final class MotdBench {

    private MotdBench() {
    }

    public static void main(String[] args) throws Exception {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        int benchSeconds = 0;
        int latencyRuns = 0;
        int threads = 4;
        int protocol = 773;
        String serverHost = "127.0.0.1";
        for (int i = 2; i < args.length - 1; i += 2) {
            switch (args[i]) {
                case "--bench" -> benchSeconds = Integer.parseInt(args[i + 1]);
                case "--latency" -> latencyRuns = Integer.parseInt(args[i + 1]);
                case "--threads" -> threads = Integer.parseInt(args[i + 1]);
                case "--protocol" -> protocol = Integer.parseInt(args[i + 1]);
                case "--server-host" -> serverHost = args[i + 1];
            }
        }

        if (latencyRuns > 0) {
            runLatency(host, port, latencyRuns, protocol, serverHost);
        } else if (benchSeconds > 0) {
            runBench(host, port, benchSeconds, threads, protocol, serverHost);
        } else {
            System.out.println(pingOnce(host, port, protocol, serverHost));
        }
    }

    private static void runLatency(String host, int port, int runs, int protocol,
                                   String serverHost) throws IOException {
        for (int i = 0; i < Math.min(300, runs); i++) {
            pingOnce(host, port, protocol, serverHost);
        }
        long start = System.nanoTime();
        for (int i = 0; i < runs; i++) {
            pingOnce(host, port, protocol, serverHost);
        }
        long elapsed = System.nanoTime() - start;
        System.out.printf("LATENCY_US %.1f%n", elapsed / (double) runs / 1000.0D);
    }

    private static void runBench(String host, int port, int seconds, int threads, int protocol,
                                 String serverHost) throws InterruptedException {
        AtomicLong successes = new AtomicLong();
        AtomicLong failures = new AtomicLong();
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            workers[t] = new Thread(() -> {
                while (System.nanoTime() < end) {
                    try {
                        pingOnce(host, port, protocol, serverHost);
                        successes.incrementAndGet();
                    } catch (IOException e) {
                        failures.incrementAndGet();
                    }
                }
            });
            workers[t].start();
        }
        for (Thread worker : workers) {
            worker.join();
        }
        long total = successes.get() + failures.get();
        System.out.printf("BENCH_QPS %.0f FAIL %d%n", total / (double) seconds, failures.get());
    }

    private static String pingOnce(String host, int port, int protocol, String serverHost) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getByName(host), port), 5000);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            byte[] hostname = serverHost.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            writeVarInt(handshake, 0x00);
            writeVarInt(handshake, protocol);
            writeVarInt(handshake, hostname.length);
            handshake.write(hostname);
            handshake.write((port >> 8) & 0xFF);
            handshake.write(port & 0xFF);
            writeVarInt(handshake, 1);
            writeFrame(out, handshake.toByteArray());
            writeFrame(out, new byte[]{0x00});
            out.flush();

            byte[] response = readFrame(in);
            int[] offset = {0};
            int packetId = readVarInt(response, offset);
            if (packetId != 0x00) {
                throw new IOException("意外包号: " + packetId);
            }
            int jsonLength = readVarInt(response, offset);
            if (jsonLength < 0 || jsonLength > response.length - offset[0]) {
                throw new IOException("响应长度异常: " + jsonLength);
            }
            String json = new String(response, offset[0], jsonLength, StandardCharsets.UTF_8);

            long time = System.currentTimeMillis();
            ByteArrayOutputStream ping = new ByteArrayOutputStream();
            writeVarInt(ping, 0x01);
            for (int shift = 56; shift >= 0; shift -= 8) {
                ping.write((int) (time >> shift));
            }
            writeFrame(out, ping.toByteArray());
            out.flush();

            byte[] pongFrame = readFrame(in);
            if (pongFrame.length == 0) {
                throw new IOException("心跳应答为空");
            }
            int[] pongOffset = {0};
            int pongId = readVarInt(pongFrame, pongOffset);
            if (pongId != 0x01 || pongFrame.length != 9) {
                throw new IOException("心跳应答异常: id=" + pongId + " len=" + pongFrame.length);
            }

            // 等待服务端主动关闭（读 EOF），客户端走被动关闭避免 TIME_WAIT 占用端口
            byte[] drain = new byte[64];
            while (in.read(drain) != -1) {
                // 丢弃残余字节
            }
            return json;
        }
    }

    private static void writeFrame(OutputStream out, byte[] packet) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        writeVarInt(frame, packet.length);
        frame.writeBytes(packet);
        out.write(frame.toByteArray());
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static byte[] readFrame(InputStream in) throws IOException {
        int length = readVarIntStream(in);
        if (length <= 0 || length > 2_097_151) {
            throw new IOException("非法帧长度: " + length);
        }
        byte[] data = in.readNBytes(length);
        if (data.length != length) {
            throw new IOException("响应不完整（期望 " + length + " 字节，实得 " + data.length + "）");
        }
        return data;
    }

    private static int readVarIntStream(InputStream in) throws IOException {
        int value = 0;
        int bits = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("连接已关闭");
            }
            value |= (b & 0x7F) << bits;
            if ((b & 0x80) == 0) {
                return value;
            }
            bits += 7;
            if (bits > 35) {
                throw new IOException("varint 过长");
            }
        }
    }

    private static int readVarInt(byte[] data, int[] offset) throws IOException {
        int value = 0;
        int bits = 0;
        while (true) {
            if (offset[0] >= data.length) {
                throw new IOException("varint 越界");
            }
            byte b = data[offset[0]++];
            value |= (b & 0x7F) << bits;
            if ((b & 0x80) == 0) {
                return value;
            }
            bits += 7;
            if (bits > 35) {
                throw new IOException("varint 过长");
            }
        }
    }
}
