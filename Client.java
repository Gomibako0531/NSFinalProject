import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

public class Client {

    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("Usage: java Client <host> <port>");
            return;
        }

        String host = args[0];
        int port = Integer.parseInt(args[1]);

        try (Socket socket = new Socket(host, port);
             BufferedReader serverIn = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter serverOut = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
             BufferedReader userIn = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {

            System.out.println("Connected to " + host + ":" + port);
            printHelp();

            AtomicReference<String> currentChannel = new AtomicReference<>(null);

            Thread reader = new Thread(() -> {
                try {
                    String line;
                    while ((line = serverIn.readLine()) != null) {
                        handleServerLine(line, serverOut);
                    }
                } catch (IOException ignored) {}
                System.out.println("Disconnected from server.");
                System.exit(0);
            });
            reader.setDaemon(true);
            reader.start();

            String input;
            while ((input = userIn.readLine()) != null) {
                input = input.trim();
                if (input.isEmpty()) continue;

                if (input.equals("/help")) {
                    printHelp();
                    continue;
                }

                if (input.startsWith("/sendfile ")) {
                    handleSendFileCommand(input, serverOut);
                    continue;
                }

                if (input.startsWith("/join ")) {
                    serverOut.println(input);
                    String[] p = input.split("\\s+", 3);
                    if (p.length >= 2) {
                        currentChannel.set(p[1]);
                        System.out.println("[INFO] currentChannel = #" + p[1]);
                    }
                    continue;
                }

                if (input.startsWith("/leave ")) {
                    serverOut.println(input);
                    String[] p = input.split("\\s+", 3);
                    if (p.length >= 2 && p[1].equals(currentChannel.get())) {
                        currentChannel.set(null);
                        System.out.println("[INFO] currentChannel cleared");
                    }
                    continue;
                }

                if (input.startsWith("/createchannel ")) {
                    serverOut.println(input);
                    String[] p = input.split("\\s+", 3);
                    if (p.length >= 2) {
                        currentChannel.set(p[1]);
                        System.out.println("[INFO] currentChannel = #" + p[1]);
                    }
                    continue;
                }

                if (input.startsWith("/")) {
                    serverOut.println(input);
                    if (input.equals("/quit")) break;
                    continue;
                }

                // Normal text -> send to current channel
                String ch = currentChannel.get();
                if (ch == null) {
                    System.out.println("[WARN] Join a channel first: /join <channel>");
                } else {
                    serverOut.println("/channelmsg " + ch + " " + input);
                }
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void printHelp() {
        System.out.println("Commands:");
        System.out.println("  /register <user> <pass>");
        System.out.println("  /login <user> <pass>");
        System.out.println("  /channels");
        System.out.println("  /createchannel <name> [password]");
        System.out.println("  /setchanpass <channel> <password>");
        System.out.println("  /join <channel> [password]");
        System.out.println("  /leave <channel>");
        System.out.println("  /mychannels");
        System.out.println("  /msg <user> <message>");
        System.out.println("  /delete <messageId>");
        System.out.println("  /sendfile <user> <filepath>");
        System.out.println("  /kick <channel> <user>");
        System.out.println("  /deletechannel <channel>");
        System.out.println("  /quit");
        System.out.println("Tip: after /join <channel>, normal text will go to that channel.");
    }

    private static void handleServerLine(String line, PrintWriter serverOut) {
        if (line.startsWith("MSG ")) {
            // MSG <id> <from> <message>
            String[] p = line.split(" ", 4);
            if (p.length >= 4) {
                System.out.println("[DM#" + p[1] + "] " + p[2] + ": " + p[3]);
                serverOut.println("/delivered " + p[1]);
            } else System.out.println(line);

        } else if (line.startsWith("CHANNELMSG ")) {
            // CHANNELMSG <id> <channel> <from> <message>
            String[] p = line.split(" ", 5);
            if (p.length >= 5) {
                System.out.println("[#" + p[2] + " #" + p[1] + "] " + p[3] + ": " + p[4]);
                serverOut.println("/delivered " + p[1]);
            } else System.out.println(line);

        } else if (line.startsWith("DELIVERED ")) {
            // DELIVERED <id> <user>
            String[] p = line.split(" ", 3);
            if (p.length == 3) System.out.println("[READ] Message #" + p[1] + " read by " + p[2]);
            else System.out.println(line);

        } else if (line.startsWith("DELETED ")) {
            // DELETED <id> <DM|channel>
            String[] p = line.split(" ", 3);
            if (p.length >= 3) System.out.println("[DELETED] Message #" + p[1] + " (" + p[2] + ")");
            else System.out.println(line);

        } else if (line.startsWith("MENTION ")) {
            // MENTION <id> <channelOrDM> <from> <message>
            String[] p = line.split(" ", 5);
            if (p.length >= 5) System.out.println("[MENTION #" + p[1] + " in " + p[2] + "] " + p[3] + ": " + p[4]);
            else System.out.println(line);

        } else if (line.startsWith("FILE_FROM ")) {
            // FILE_FROM <from> <filename> <base64>
            String[] p = line.split(" ", 4);
            if (p.length == 4) {
                System.out.println("[FILE] from " + p[1] + ": " + p[2] + " (received)");
                saveIncomingFile(p[2], p[3]);
            } else System.out.println(line);

        } else if (line.equals("CHANNELS_BEGIN")) {
            System.out.println("[CHANNEL LIST]");
        } else if (line.equals("CHANNELS_END")) {
            System.out.println("[END CHANNEL LIST]");
        } else if (line.startsWith("CHANNEL ")) {
            System.out.println("  " + line.substring("CHANNEL ".length()));
        } else {
            System.out.println(line);
        }
    }

    // User input: /sendfile <user> <filepath>
    // Sends:      /sendfile <user> <filename> <base64>
    private static void handleSendFileCommand(String input, PrintWriter serverOut) {
        String[] p = input.split("\\s+", 3);
        if (p.length < 3) {
            System.out.println("Usage: /sendfile <user> <filepath>");
            return;
        }
        String to = p[1];
        String path = p[2];

        File f = new File(path);
        if (!f.exists() || !f.isFile()) {
            System.out.println("File not found: " + path);
            return;
        }

        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(f))) {
            byte[] bytes = bis.readAllBytes();
            String b64 = Base64.getEncoder().encodeToString(bytes);
            serverOut.println("/sendfile " + to + " " + f.getName() + " " + b64);
            System.out.println("[FILE] Sent " + f.getName() + " (" + bytes.length + " bytes) to " + to);
        } catch (IOException e) {
            System.out.println("File read error: " + e.getMessage());
        }
    }

    private static void saveIncomingFile(String filename, String base64) {
        String safe = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
        File out = new File("received_" + safe);

        try {
            byte[] data = Base64.getDecoder().decode(base64);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(data);
            }
            System.out.println("[FILE] Saved as: " + out.getName());
        } catch (IllegalArgumentException e) {
            System.out.println("[FILE] Invalid base64 data.");
        } catch (IOException e) {
            System.out.println("[FILE] Save failed: " + e.getMessage());
        }
    }
}
