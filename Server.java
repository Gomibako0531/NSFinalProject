import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

public class Server {

    // username -> handler
    private static final Map<String, ClientHandler> clients = new ConcurrentHashMap<>();

    // channel -> Channel
    private static final Map<String, Channel> channels = new ConcurrentHashMap<>();

    // messageId -> sender username (for read receipt)
    private static final Map<Long, String> messageSenders = new ConcurrentHashMap<>();
    private static final AtomicLong msgId = new AtomicLong(1);

    private static class Channel {
        final String name;
        final String owner;
        volatile String password; // null = no password
        final CopyOnWriteArraySet<ClientHandler> members = new CopyOnWriteArraySet<>();

        Channel(String name, String owner, String password) {
            this.name = name;
            this.owner = owner;
            this.password = (password == null || password.isBlank()) ? null : password;
        }
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: java Server <port>");
            return;
        }
        int port = Integer.parseInt(args[0]);

        try (ServerSocket ss = new ServerSocket(port)) {
            System.out.println("Server running on port " + port);
            while (true) {
                Socket s = ss.accept();
                new Thread(new ClientHandler(s)).start();
            }
        } catch (java.net.BindException be) {
            System.out.println("Server error: Address already in use (port " + port + ")");
            System.out.println("Fix: use another port, or stop the process using it:");
            System.out.println("  lsof -nP -iTCP:" + port + " -sTCP:LISTEN");
            System.out.println("  kill <PID>");
        } catch (IOException e) {
            System.out.println("Server error:");
            e.printStackTrace();
        }
    }

    private static void broadcastToChannel(Channel ch, String line) {
        for (ClientHandler m : ch.members) {
            m.send(line);
        }
    }

    private static void sendToUser(String username, String line) {
        ClientHandler h = clients.get(username);
        if (h != null) h.send(line);
    }

    private static void removeFromAllChannels(ClientHandler h) {
        for (Channel c : channels.values()) c.members.remove(h);
    }

    private static class ClientHandler implements Runnable {
        private final Socket socket;
        private BufferedReader in;
        private PrintWriter out;
        private String username;

        ClientHandler(Socket socket) {
            this.socket = socket;
        }

        void send(String line) {
            if (out != null) out.println(line);
        }

        @Override
        public void run() {
            try {
                in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

                send("INFO Welcome.");
                send("INFO Commands: /login /msg /createchannel /setchanpass /join /channelmsg /sendfile /delivered /kick /deletechannel /mychannels /quit");

                String line;
                while ((line = in.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    if (line.startsWith("/")) {
                        handleCommand(line);
                    } else {
                        // Optional fallback: global broadcast
                        if (!ensureLogin()) continue;
                        long id = msgId.getAndIncrement();
                        messageSenders.put(id, username);
                        for (ClientHandler h : clients.values()) {
                            h.send("MSG " + id + " " + username + " " + line);
                        }
                    }
                }
            } catch (IOException ignored) {
            } finally {
                if (username != null) clients.remove(username);
                removeFromAllChannels(this);
                try { socket.close(); } catch (IOException ignored) {}
            }
        }

        private boolean ensureLogin() {
            if (username == null) {
                send("ERR You must /login first.");
                return false;
            }
            return true;
        }

        private void handleCommand(String line) {
            String[] parts = line.split("\\s+", 3);
            String cmd = parts[0];

            switch (cmd) {
                case "/login": login(parts); break;
                case "/msg": dm(parts); break;

                case "/createchannel": createChannel(line); break;
                case "/setchanpass": setChanPass(parts); break;
                case "/join": join(line); break;
                case "/channelmsg": channelMsg(parts); break;

                case "/sendfile": sendFile(parts); break;

                case "/delivered": delivered(parts); break;

                case "/kick": kick(parts); break;
                case "/deletechannel": deleteChannel(parts); break;

                case "/mychannels": myChannels(); break;

                case "/quit":
                    send("INFO Bye!");
                    try { socket.close(); } catch (IOException ignored) {}
                    break;

                default:
                    send("ERR Unknown command: " + cmd);
            }
        }

        // /login <username>
        private void login(String[] parts) {
            if (parts.length < 2) { send("ERR Usage: /login <username>"); return; }
            if (username != null) { send("ERR Already logged in."); return; }

            String name = parts[1].trim();
            if (name.isEmpty()) { send("ERR Empty username"); return; }

            if (clients.putIfAbsent(name, this) != null) {
                send("ERR USERNAME_TAKEN");
                return;
            }
            username = name;
            send("OK LOGIN " + username);
        }

        // /msg <user> <message>
        private void dm(String[] parts) {
            if (!ensureLogin()) return;
            if (parts.length < 3) { send("ERR Usage: /msg <user> <message>"); return; }

            String to = parts[1];
            String msg = parts[2];
            if (!clients.containsKey(to)) { send("ERR User not found: " + to); return; }

            long id = msgId.getAndIncrement();
            messageSenders.put(id, username);

            sendToUser(to, "MSG " + id + " " + username + " " + msg);
            send("MSG " + id + " " + username + " (to " + to + ") " + msg);

            // mention notify in DM if contains @<to>
            if (msg.contains("@" + to)) {
                sendToUser(to, "MENTION " + id + " DM " + username + " " + msg);
            }
        }

        // /createchannel <name> [password]
        private void createChannel(String line) {
            if (!ensureLogin()) return;
            String[] t = line.split("\\s+", 3);
            if (t.length < 2) { send("ERR Usage: /createchannel <name> [password]"); return; }

            String name = t[1];
            String pass = (t.length == 3) ? t[2] : null;

            if (channels.containsKey(name)) { send("ERR Channel exists: " + name); return; }

            Channel ch = new Channel(name, username, pass);
            ch.members.add(this);
            channels.put(name, ch);

            send("OK CREATECHANNEL " + name + " owner=" + username);
            send("OK JOIN " + name);
        }

        // /setchanpass <channel> <password>
        private void setChanPass(String[] parts) {
            if (!ensureLogin()) return;
            if (parts.length < 3) { send("ERR Usage: /setchanpass <channel> <password>"); return; }

            String channel = parts[1];
            String pass = parts[2];

            Channel ch = channels.get(channel);
            if (ch == null) { send("ERR Channel not found: " + channel); return; }
            if (!ch.owner.equals(username)) { send("ERR Only owner can set password."); return; }

            ch.password = pass.isBlank() ? null : pass;
            send("OK SETCHANPASS " + channel);
        }

        // /join <channel> [password]
        private void join(String line) {
            if (!ensureLogin()) return;
            String[] t = line.split("\\s+", 3);
            if (t.length < 2) { send("ERR Usage: /join <channel> [password]"); return; }

            String channel = t[1];
            String pass = (t.length == 3) ? t[2] : null;

            Channel ch = channels.get(channel);
            if (ch == null) { send("ERR Channel does not exist. Use /createchannel."); return; }

            if (ch.password != null) {
                if (pass == null || !ch.password.equals(pass)) {
                    send("ERR Wrong password for channel " + channel);
                    return;
                }
            }

            ch.members.add(this);
            send("OK JOIN " + channel);
            broadcastToChannel(ch, "INFO [" + channel + "] " + username + " joined.");
        }

        // /channelmsg <channel> <message>
        private void channelMsg(String[] parts) {
            if (!ensureLogin()) return;
            if (parts.length < 3) { send("ERR Usage: /channelmsg <channel> <message>"); return; }

            String channel = parts[1];
            String msg = parts[2];

            Channel ch = channels.get(channel);
            if (ch == null) { send("ERR Channel not found: " + channel); return; }
            if (!ch.members.contains(this)) { send("ERR You are not in channel: " + channel); return; }

            long id = msgId.getAndIncrement();
            messageSenders.put(id, username);

            broadcastToChannel(ch, "CHANNELMSG " + id + " " + channel + " " + username + " " + msg);

            // mention notifications: @username
            for (ClientHandler m : ch.members) {
                if (m.username == null) continue;
                if (!m.username.equals(username) && msg.contains("@" + m.username)) {
                    m.send("MENTION " + id + " " + channel + " " + username + " " + msg);
                }
            }
        }

        // /sendfile <user> <filename> <base64>
        private void sendFile(String[] parts) {
            if (!ensureLogin()) return;
            if (parts.length < 3) { send("ERR Usage: /sendfile <user> <filename> <base64>"); return; }

            String to = parts[1];
            if (!clients.containsKey(to)) { send("ERR User not found: " + to); return; }

            String rest = parts[2];
            int sp = rest.indexOf(' ');
            if (sp <= 0) { send("ERR Usage: /sendfile <user> <filename> <base64>"); return; }

            String filename = rest.substring(0, sp);
            String b64 = rest.substring(sp + 1);

            try { Base64.getDecoder().decode(b64); }
            catch (IllegalArgumentException e) { send("ERR Invalid base64"); return; }

            sendToUser(to, "FILE_FROM " + username + " " + filename + " " + b64);
            send("INFO File sent to " + to + ": " + filename);
        }

        // /delivered <messageId>
        private void delivered(String[] parts) {
            if (!ensureLogin()) return;
            if (parts.length < 2) { send("ERR Usage: /delivered <messageId>"); return; }

            long id;
            try { id = Long.parseLong(parts[1]); }
            catch (NumberFormatException e) { send("ERR Invalid messageId"); return; }

            String sender = messageSenders.get(id);
            if (sender == null) return;

            ClientHandler senderH = clients.get(sender);
            if (senderH != null && !sender.equals(username)) {
                senderH.send("DELIVERED " + id + " " + username);
            }
        }

        // /kick <channel> <user>
        private void kick(String[] parts) {
            if (!ensureLogin()) return;
            if (parts.length < 3) { send("ERR Usage: /kick <channel> <user>"); return; }

            String channel = parts[1];
            String targetUser = parts[2];

            Channel ch = channels.get(channel);
            if (ch == null) { send("ERR Channel not found: " + channel); return; }
            if (!ch.owner.equals(username)) { send("ERR Only owner can kick."); return; }

            ClientHandler target = clients.get(targetUser);
            if (target == null || !ch.members.contains(target)) {
                send("ERR User not in channel.");
                return;
            }

            ch.members.remove(target);
            target.send("INFO You were removed from #" + channel + " by " + username);
            broadcastToChannel(ch, "INFO [" + channel + "] " + targetUser + " was removed by " + username);
        }

        // /deletechannel <channel>
        private void deleteChannel(String[] parts) {
            if (!ensureLogin()) return;
            if (parts.length < 2) { send("ERR Usage: /deletechannel <channel>"); return; }

            String channel = parts[1];
            Channel ch = channels.get(channel);
            if (ch == null) { send("ERR Channel not found: " + channel); return; }
            if (!ch.owner.equals(username)) { send("ERR Only owner can delete."); return; }

            broadcastToChannel(ch, "INFO Channel #" + channel + " was deleted by " + username);
            channels.remove(channel);
            send("OK DELETECHANNEL " + channel);
        }

        // /mychannels
        private void myChannels() {
            if (!ensureLogin()) return;
            StringBuilder sb = new StringBuilder("MYCHANNELS");
            for (Channel ch : channels.values()) {
                if (ch.members.contains(this)) sb.append(" ").append(ch.name);
            }
            send(sb.toString());
        }

        // /leave <channel>
        private void leaveChannel(String[] parts) {
            if (!ensureLogin()) return;
            if (parts.length < 2) {
            send("ERR Usage: /leave <channel>");
            return;
            }

            String channelName = parts[1];
            Channel ch = channels.get(channelName);

            if (ch == null) {
                send("ERR Channel not found: " + channelName);
                return;
            }

            if (!ch.members.contains(this)) {
                send("ERR You are not in channel: " + channelName);
                return;
            }

            ch.members.remove(this);
            send("OK LEAVE " + channelName);

            broadcastToChannel(ch, "INFO [" + channelName + "] " + username + " left the channel.");
        }
    }
}
