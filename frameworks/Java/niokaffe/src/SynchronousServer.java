import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

public class SynchronousServer {

    static String dateHeader = "EEE, dd MMM yyyy HH:mm:ss z\n";
    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    {
        scheduler.scheduleAtFixedRate(() -> {
            Calendar calendar = Calendar.getInstance();
            SimpleDateFormat dateFormat = new SimpleDateFormat(
                    "EEE, dd MMM yyyy HH:mm:ss z\n", Locale.US);
            dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
            dateHeader = "Date: " + dateFormat.format(calendar.getTime());
        }, 0, 1, TimeUnit.SECONDS);
    }

    ByteBuffer httpResponse(String status, String body) {
        String result = "HTTP/1.1 " + status + "\n"
                + "Server: niokaffe\n"
                + dateHeader
                + "Content-Length: " + body.length() + "\n"
                + "Content-Type: text/plain\n"
                + "Connection: close\n"
                + "\n"
                + body;
        return ByteBuffer.wrap(result.getBytes(StandardCharsets.UTF_8));
    }

    static class RouteEntry {
        byte[] route;
        Function<ByteBuffer, ByteBuffer> handler;
        public RouteEntry(String route, Function<ByteBuffer, ByteBuffer> handler) {
            this.route = route.getBytes(StandardCharsets.UTF_8);
            this.handler = handler;
        }
    }

    List<RouteEntry> routeEntries = new ArrayList<>();

    void addRoute(String route, Function<ByteBuffer, ByteBuffer> handler) {
        routeEntries.add(new RouteEntry(route, handler));
    }

    {
        addRoute("GET /plaintext", (req) -> httpResponse("200 OK", "Hello, world!"));
    }

    ByteBuffer route(ByteBuffer request) {
        int fastSkips = 0;
        nextEntry:
        for (RouteEntry entry : routeEntries) {
            if (request.position() < entry.route.length) {
                fastSkips++;
                continue;
            }
            ByteBuffer dupe = request.duplicate();
            dupe.rewind();
            for (int i = 0; i < entry.route.length; i++) {
                if (entry.route[i] != dupe.get()) continue nextEntry;
            }
            if (dupe.get() != 0x20) continue;
            return entry.handler.apply(request);
        }
        return fastSkips == routeEntries.size() ? null : httpResponse("404 Not Found", "");
    }

    ServerSocketChannel server;

    class ConnectionWorker extends Thread {

        LinkedList<Connection> connections = new LinkedList<>();

        void acceptConnections() {
            for (;;) {
                try {
                    SocketChannel client = server.accept();
                    if (client != null) {
                        client.configureBlocking(false);
                        connections.addFirst(new Connection(client));
                    } else {
                        return;
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        public void run() {
            for (;;) {
                acceptConnections();
                for (Connection c : connections) {
                    c.resume.run();
                    if (c.resume == null)
                        connections.removeFirstOccurrence(c);
                }
            }
        }
    }

    class Connection {
        SocketChannel client;
        ByteBuffer request = ByteBuffer.allocate(1024);
        ByteBuffer response;
        Runnable resume = this::establishConnection;

        public Connection(SocketChannel client) {
            this.client = client;
        }

        void establishConnection() {
            try {
                if (client.finishConnect()) {
                    resume = this::readRequest;
                    readRequest();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        void readRequest() {
            try {
                int bytes = client.read(request);
                if (bytes > 0) {
                    routeRequest();
                }
            } catch (IOException e) {
            }
        }

        void routeRequest() {
            response = route(request);
            if (response != null) {
                writeResponse();
                resume = this::writeResponse;
            }
        }

        private void writeResponse() {
            try {
                // read more?
                int remaining = response.remaining();
                int written = client.write(response);
                if (written == remaining) {
                    client.close();
                    resume = null;
                }
            } catch (IOException e) {
            }
        }
    }

    void start() {
        int numWorkers = 8;
        try {
            server = ServerSocketChannel.open();
            server.bind(new InetSocketAddress(Inet4Address.getLocalHost(), 8080));
        } catch (IOException e) {
            e.printStackTrace();
        }
        for (int i = 0; i < numWorkers - 1; i++) {
            new ConnectionWorker().start();
        }
        new ConnectionWorker().run();
    }

    public static void main(String[] args) {
        new SynchronousServer().start();
    }
}
