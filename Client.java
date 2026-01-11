import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class Client {

    public static void main(String[] args) {
        // Require host + port args
        if (args.length != 2) {
            System.out.println("Usage: java Client <host> <port>");
            return;
        }

        String host = args[0];
        int port = Integer.parseInt(args[1]);
        // Connect to server and setup text streams (UTF-8)
        try (Socket socket = new Socket(host, port);
             BufferedReader serverIn = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter serverOut = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
             BufferedReader userIn = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {

            System.out.println("Connected to " + host + ":" + port);
            printHelp();
            // Read server messages asynchronously so the user can type at the same time
            Thread reader = new Thread(() -> {
                try {
                    String line;
                    while ((line = serverIn.readLine()) != null) {
                        handleServerLine(line, serverOut);
                    }
                } catch (IOException ignored) {}
                // Server closed the connection
                System.out.println("Disconnected from server.");
                System.exit(0);
            });
            reader.setDaemon(true);
            reader.start();
            // Read user commands and forward them to the server
            String input;
            while ((input = userIn.readLine()) != null) {
                input = input.trim();
                if (input.isEmpty()) continue;
                // Local help (not sent to server)
                if (input.equals("/help")) { printHelp(); continue; }
                // Client-side file sending (convert file -> base64 -> /sendfile)
                if (input.startsWith("/sendfile ")) {
                    handleSendFileCommand(input, serverOut);
                    continue;
                }
                // Enforce command-only mode (no plain text)
                if (!input.startsWith("/")) {
                    System.out.println("[ERROR] Plain text sending is disabled.");
                    System.out.println("        Use /msg <target> <message>");
                    continue;
                }
                // Send raw command line to server
                serverOut.println(input);
                if (input.equals("/quit")) break;
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    // Print supported client commands (protocol overview)
    private static void printHelp() {
        System.out.println("Commands:\n");
        System.out.println("  /register <user> <pass>\n");
        System.out.println("  /login <user> <pass>\n");
        System.out.println("  /channels\n");
        System.out.println("  /createchannel <name> [password]\n");
        System.out.println("  /join <channel> [password]\n");
        System.out.println("  /leave <channel>\n");
        System.out.println("  /mychannels\n");
        System.out.println("  /msg <target> <message>    (target=user => DM, target=channel => channel msg)\n");
        System.out.println("  /delete <messageId>\n");
        System.out.println("  /sendfile <user> <filepath>\n");
        System.out.println("  /kick <channel> <user>\n");
        System.out.println("  /deletechannel <channel>\n");
        System.out.println("  /createpoll <title> <opt1|opt2|...>\n");
        System.out.println("  /answerpoll <pollId> <choice>\n");
        System.out.println("  /pollresults <pollId>\n");
        System.out.println("  /closepoll <pollId>\n");
        System.out.println("  /quit\n");
        System.out.println("NOTE: Plain text sending is disabled. Use /msg explicitly.\n");
    }

    // Decode one server line and print a user-friendly message
    private static void handleServerLine(String line, PrintWriter serverOut) {
        if (line.startsWith("MSG ")) {
            // MSG <id> <from> <text...>
            String[] p = line.split(" ", 4);
            if (p.length >= 4) {
                System.out.println("[DM#" + p[1] + "] " + p[2] + ": " + p[3]);
                serverOut.println("/delivered " + p[1]);
            } else System.out.println(line);

        } else if (line.startsWith("CHANNELMSG ")) {
            // CHANNELMSG <id> <channel> <from> <text...>
            String[] p = line.split(" ", 5);
            if (p.length >= 5) {
                System.out.println("[#" + p[2] + " #" + p[1] + "] " + p[3] + ": " + p[4]);
                serverOut.println("/delivered " + p[1]);
            } else System.out.println(line);

        } else if (line.startsWith("DELIVERED ")) {
            // DELIVERED <messageId> <readerUsername>
            String[] p = line.split(" ", 3);
            if (p.length == 3) System.out.println("[READ] Message #" + p[1] + " read by " + p[2]);
            else System.out.println(line);

        } else if (line.startsWith("DELETED ")) {
            // DELETED <messageId> <DM|channelName>
            String[] p = line.split(" ", 3);
            if (p.length >= 3) System.out.println("[DELETED] Message #" + p[1] + " (" + p[2] + ")");
            else System.out.println(line);

        } else if (line.startsWith("MENTION ")) {
            // MENTION <id> <context> <from> <text...>
            String[] p = line.split(" ", 5);
            if (p.length >= 5) System.out.println("[MENTION #" + p[1] + " in " + p[2] + "] " + p[3] + ": " + p[4]);
            else System.out.println(line);

        } else if (line.startsWith("FILE_FROM ")) {
            // FILE_FROM <from> <filename> <base64>
            String[] p = line.split(" ", 4);
            if (p.length == 4) {
                System.out.println("[FILE] from " + p[1] + ": " + p[2] + " (received)");
                // Save received file locally
                saveIncomingFile(p[2], p[3]);
            } else System.out.println(line);

        } else if (line.equals("CHANNELS_BEGIN")) {
            // Begin channel list output
            System.out.println("[CHANNEL LIST]");
        } else if (line.equals("CHANNELS_END")) {
            // End channel list output
            System.out.println("[END CHANNEL LIST]");
        } else if (line.startsWith("CHANNEL ")) {
            // One channel entry line
            System.out.println("  " + line.substring("CHANNEL ".length()));

        } else if (line.startsWith("POLL_CREATED ")) {
            // POLL_CREATED <channel> <pollId> <title>
            String[] p = line.split(" ", 4);
            if (p.length >= 4) System.out.println("[POLL CREATED #" + p[2] + " in #" + p[1] + "] " + p[3]);
            else System.out.println(line);

        } else if (line.startsWith("POLL_ACTIVE ")) {
            // POLL_ACTIVE <channel> <pollId> <title>
            String[] p = line.split(" ", 4);
            if (p.length >= 4) System.out.println("[POLL ACTIVE #" + p[2] + " in #" + p[1] + "] " + p[3]);
            else System.out.println(line);

        } else if (line.startsWith("POLL_RESULTS ")) {
            // POLL_RESULTS <channel> <pollId> <title...>
            String[] p = line.split(" ", 4);
            if (p.length >= 4) System.out.println("[POLL RESULTS #" + p[2] + " in #" + p[1] + "] " + p[3]);
            else System.out.println(line);

        } else if (line.startsWith("POLL_CLOSED ")) {
            // POLL_CLOSED <channel> <pollId> <title>
            String[] p = line.split(" ", 4);
            if (p.length >= 4) System.out.println("[POLL CLOSED #" + p[2] + " in #" + p[1] + "] " + p[3]);
            else System.out.println(line);

        } else if (line.startsWith("POLL_OPTIONS ")) {
            // POLL_OPTIONS <channel> <optionsLine>
            String[] p = line.split(" ", 3);
            if (p.length >= 3) System.out.println("[POLL OPTIONS in #" + p[1] + "] " + p[2]);
            else System.out.println(line);

        } else if (line.startsWith("POLL_HOWTO ")) {
            // POLL_HOWTO <channel> <helpText...>
            String[] p = line.split(" ", 3);
            if (p.length >= 3) System.out.println("[POLL HOWTO in #" + p[1] + "] " + p[2]);
            else System.out.println(line);

        } else if (line.startsWith("POLL_VOTE ")) {
            // Poll vote event line (already human-readable enough)

            System.out.println("[POLL] " + line.substring("POLL_VOTE ".length()));

        } else if (line.startsWith("POLL_TALLY ")) {
            // POLL_TALLY <channel> <pollId> <tallyLine>
            String[] p = line.split(" ", 4);
            if (p.length >= 4) System.out.println("[POLL TALLY #" + p[2] + " in #" + p[1] + "] " + p[3]);
            else System.out.println(line);

        } else {
            // Fallback: print raw server line
            System.out.println(line);
        }
    }

    // /sendfile <user> <filepath>
    // Convert a local file to base64 and send it using the protocol
    private static void handleSendFileCommand(String input, PrintWriter serverOut) {
        String[] p = input.split("\\s+", 3);
        if (p.length < 3) {
            System.out.println("Usage: /sendfile <user> <filepath>");
            return;
        }
        String to = p[1];
        String path = p[2];

        // Validate input file path
        File f = new File(path);
        if (!f.exists() || !f.isFile()) {
            System.out.println("File not found: " + path);
            return;
        }

        // Read file bytes and encode as base64 for transport over text protocol
        try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(f))) {
            byte[] bytes = bis.readAllBytes();
            String b64 = Base64.getEncoder().encodeToString(bytes);
            serverOut.println("/sendfile " + to + " " + f.getName() + " " + b64);
            System.out.println("[FILE] Sent " + f.getName() + " (" + bytes.length + " bytes) to " + to);
        } catch (IOException e) {
            System.out.println("File read error: " + e.getMessage());
        }
    }

    // Save a received base64 file payload to "received_<filename>"
    private static void saveIncomingFile(String filename, String base64) {
        // Sanitize filename to avoid unsafe characters
        String safe = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
        File out = new File("received_" + safe);


        try {
            // Decode base64 and write bytes to disk
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
