import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AlreadyConnectedException;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

public class Server {

    static String dateHeader = "EEE, dd MMM yyyy HH:mm:ss z\n";
    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    List<RouteEntry> routeEntries = new ArrayList<>();
    AsynchronousServerSocketChannel server;

    {
        scheduler.scheduleAtFixedRate(() -> {
            Calendar calendar = Calendar.getInstance();
            SimpleDateFormat dateFormat = new SimpleDateFormat(
                    "EEE, dd MMM yyyy HH:mm:ss z\n", Locale.US);
            dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
            dateHeader = "Date: " + dateFormat.format(calendar.getTime());
        }, 0, 1, TimeUnit.SECONDS);
    }

    {
        addRoute("GET /plaintext", (req) -> httpResponse("200 OK", "Hello, world!"));
    }

    static <T> CompletionHandler<T, Object> handler(Consumer<T> completed, Consumer<Throwable> failed) {
        return new CompletionHandler<>() {
            @Override
            public void completed(T result, Object attachment) {
                completed.accept(result);
            }

            @Override
            public void failed(Throwable exc, Object attachment) {
                failed.accept(exc);
            }
        };
    }

    public static void main(String[] args) {
        new Server().start();
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

    void addRoute(String route, Function<ByteBuffer, ByteBuffer> handler) {
        routeEntries.add(new RouteEntry(route, handler));
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

    void acceptConnection() {
        server.accept(null,
                handler(
                        client -> {
                            pendingConnection(client);
                            acceptConnection();
                        },
                        exception ->
                                acceptConnection()
                ));
    }

    void pendingConnection(AsynchronousSocketChannel client) {
        try {
            SocketAddress address = client.getRemoteAddress();
            client.connect(address, null,
                    handler(
                            voided -> {
                                readRequest(client, ByteBuffer.allocate(1024));
                            }, e -> {
                                if (e instanceof AlreadyConnectedException)
                                    readRequest(client, ByteBuffer.allocate(1024));
                            }
                    ));
        } catch (IOException ignored) {
        } catch (AlreadyConnectedException ignored) {
            readRequest(client, ByteBuffer.allocate(1024));
        }

    }

    private void readRequest(AsynchronousSocketChannel client, ByteBuffer request) {
        client.read(request, null, handler(
                bytesRead -> {
                    if (bytesRead > 0) {
                        routeRequest(client, request);
                    } else {
                        readRequest(client, request);
                    }
                },
                e -> {
                    // TODO
                }
        ));
    }

    private void routeRequest(AsynchronousSocketChannel client, ByteBuffer request) {
        ByteBuffer response = route(request);
        if (response == null) {
            readRequest(client, request);
        } else {
            writeResponse(client, response);
        }
    }

    private void writeResponse(AsynchronousSocketChannel client, ByteBuffer response) {
        int remaining = response.remaining();
        client.write(response, null, handler(
                written -> {
                    if (written == remaining) {
                        try {
                            client.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    } else {
                        writeResponse(client, response);
                    }
                },
                e -> {
                    // TODO
                }
        ));
    }

    void start() {
        try {
            // todo try with custom channel group executor
            server = AsynchronousServerSocketChannel.open();
            server.bind(new InetSocketAddress(Inet4Address.getLocalHost(), 8080));
            acceptConnection();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    static class RouteEntry {
        byte[] route;
        Function<ByteBuffer, ByteBuffer> handler;

        public RouteEntry(String route, Function<ByteBuffer, ByteBuffer> handler) {
            this.route = route.getBytes(StandardCharsets.UTF_8);
            this.handler = handler;
        }
    }
}
