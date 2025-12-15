import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

public class Server {

    // username -> handler (online users)
    private static final Map<String, ClientHandler> clients = new ConcurrentHashMap<>();

    // channel -> Channel
    private static final Map<String, Channel> channels = new ConcurrentHashMap<>();

    // auth: username -> AuthRecord (salt + hash)
    private static final Map<String, AuthRecord> auth = new ConcurrentHashMap<>();

    // messageId -> sender username (read receipt)
    private static final Map<Long, String> messageSenders = new ConcurrentHashMap<>();

    // messageId -> MessageRecord (for delete)
    private static final Map<Long, MessageRecord> messages = new ConcurrentHashMap<>();

    private static final AtomicLong msgId = new AtomicLong(1);

    // ---------- Auth ----------
    private static class AuthRecord {
        final byte[] salt;
        final byte[] hash;
        AuthRecord(byte[] salt, byte[] hash) { this.salt = salt; this.hash = hash; }
    }

    private static byte[] sha256(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(input);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static AuthRecord makeAuth(String password) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] passBytes = password.getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[salt.length + passBytes.length];
        System.arraycopy(salt, 0, combined, 0, salt.length);
        System.arraycopy(passBytes, 0, combined, salt.length, passBytes.length);
        byte[] hash = sha256(combined);
        return new AuthRecord(salt, hash);
    }

    private static boolean verifyAuth(AuthRecord rec, String password) {
        byte[] passBytes = password.getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[rec.salt.length + passBytes.length];
        System.arraycopy(rec.salt, 0, combined, 0, rec.salt.length);
        System.arraycopy(passBytes, 0, combined, rec.salt.length, passBytes.length);
        byte[] hash = sha256(combined);
        return Arrays.equals(hash, rec.hash);
    }

    // ---------- Channel ----------
    private static class Channel {
        final String name;
        final String owner;
        volatile String password; // null = none
        final CopyOnWriteArraySet<ClientHandler> members = new CopyOnWriteArraySet<>();

        Channel(String name, String owner, String password) {
            this.name = name;
            this.owner = owner;
            this.password = (password == null || password.isBlank()) ? null : password;
        }
    }

    // ---------- Message record ----------
    private enum MsgType { DM, CHANNEL }

    private static class MessageRecord {
        final long id;
        final MsgType type;
        final String from;
        final String to;      // DM target, else null
        final String channel; // channel name, else null

        MessageRecord(long id, MsgType type, String from, String to, String channel) {
            this.id = id;
            this.type = type;
            this.from = from;
            this.to = to;
            this.channel = channel;
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
            System.out.println("Fix:");
            System.out.println("  lsof -nP -iTCP:" + port + " -sTCP:LISTEN");
            System.out.println("  kill <PID>");
        } catch (IOException e) {
            System.out.println("Server error:");
            e.printStackTrace();
        }
    }

    private static void broadcastToChannel(Channel ch, String line) {
        for (ClientHandler m : ch.members) m.send(line);
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
        private String username; // logged-in name

        ClientHandler(Socket socket) { this.socket = socket; }

        void send(String line) {
            if (out != null) out.println(line);
        }

        @Override
        public void run() {
            try {
                in  = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

                send("INFO Welcome.");
                send("INFO Auth: /register <user> <pass> then /login <user> <pass>");
                send("INFO Commands: /msg /createchannel /setchanpass /channels /join /leave /channelmsg /sendfile /delete /delivered /kick /deletechannel /mychannels /quit");

                String line;
                while ((line = in.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    if (line.startsWith("/")) handleCommand(line);
                    else {
                        send("ERR Please use channels or DM. (Join a channel or use /msg)");
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
                // auth
                case "/register": register(parts); break;
                case "/login": login(parts); break;

                // messaging
                case "/msg": dm(parts); break;
                case "/delete": deleteMessage(parts); break;
                case "/delivered": delivered(parts); break;

                // channels
                case "/channels": listChannels(); break;
                case "/createchannel": createChannel(line); break;
                case "/setchanpass": setChanPass(parts); break;
                case "/join": join(line); break;
                case "/leave": leaveChannel(parts); break;
                case "/channelmsg": channelMsg(parts); break;

                // file
                case "/sendfile": sendFile(parts); break;

                // admin
                case "/kick": kick(parts); break;
                case "/deletechannel": deleteChannel(parts); break;

                // info
                case "/mychannels": myChannels(); break;

                case "/quit":
                    send("INFO Bye!");
                    try { socket.close(); } catch (IOException ignored) {}
                    break;

                default:
                    send("ERR Unknown command: " + cmd);
            }
        }

        // /register <user> <pass>
        private void register(String[] parts) {
            if (parts.length < 3) { send("ERR Usage: /register <user> <pass>"); return; }
            String user = parts[1].trim();
            String pass = parts[2];

            if (user.isEmpty()) { send("ERR Empty username"); return; }
            if (auth.containsKey(user)) { send("ERR USER_ALREADY_EXISTS"); return; }

            auth.put(user, makeAuth(pass));
            send("OK REGISTER " + user);
        }

        // /login <user> <pass>
        private void login(String[] parts) {
            if (parts.length < 3) { send("ERR Usage: /login <user> <pass>"); return; }
            if (username != null) { send("ERR Already logged in as " + username); return; }

            String user = parts[1].trim();
            String pass = parts[2];

            AuthRecord rec = auth.get(user);
            if (rec == null) { send("ERR NO_SUCH_USER"); return; }
            if (!verifyAuth(rec, pass)) { send("ERR WRONG_PASSWORD"); return; }

            // prevent double login
            if (clients.putIfAbsent(user, this) != null) {
                send("ERR USER_ALREADY_ONLINE");
                return;
            }

            username = user;
            send("OK LOGIN " + username);
        }

        // /msg <user> <message>
        private void dm(String[] parts) {
            if (!ensureLogin()) return;
            if (parts.length < 3) { send("ERR Usage: /msg <user> <message>"); return; }

            String to = parts[1];
            String msg = parts[2];

            if (!auth.containsKey(to)) { send("ERR No such user: " + to); return; }
            if (!clients.containsKey(to)) { send("ERR User is offline: " + to); return; }

            long id = msgId.getAndIncrement();
            messageSenders.put(id, username);
            messages.put(id, new MessageRecord(id, MsgType.DM, username, to, null));

            sendToUser(to, "MSG " + id + " " + username + " " + msg);
            send("MSG " + id + " " + username + " (to " + to + ") " + msg);

            // mention notify in DM if contains @<to>
            if (msg.contains("@" + to)) {
                sendToUser(to, "MENTION " + id + " DM " + username + " " + msg);
            }
        }

        // /delete <messageId>
        private void deleteMessage(String[] parts) {
            if (!ensureLogin()) return;
            if (parts.length < 2) { send("ERR Usage: /delete <messageId>"); return; }

            long id;
            try { id = Long.parseLong(parts[1]); }
            catch (NumberFormatException e) { send("ERR Invalid messageId"); return; }

            MessageRecord rec = messages.get(id);
            if (rec == null) { send("ERR No such messageId"); return; }

            boolean allowed = false;

            if (rec.type == MsgType.DM) {
                // only sender can delete DM
                allowed = rec.from.equals(username);
            } else if (rec.type == MsgType.CHANNEL) {
                // sender OR channel owner can delete
                Channel ch = channels.get(rec.channel);
                if (ch != null) {
                    allowed = rec.from.equals(username) || ch.owner.equals(username);
                }
            }

            if (!allowed) { send("ERR Not allowed to delete this message"); return; }

            // remove record
            messages.remove(id);
            messageSenders.remove(id);

            // notify recipients
            if (rec.type == MsgType.DM) {
                send("OK DELETE " + id);
                sendToUser(rec.to, "DELETED " + id + " DM");
            } else {
                Channel ch = channels.get(rec.channel);
                if (ch != null) {
                    send("OK DELETE " + id);
                    broadcastToChannel(ch, "DELETED " + id + " " + rec.channel);
                } else {
                    send("ERR Channel missing for this message");
                }
            }
        }

        // /delivered <messageId>
        private void delivered(String[] parts) {
            if (!ensureLogin()) return;
            if (parts.length < 2) { send("ERR Usage: /delivered <messageId>"); return; }

            long id;
            try { id = Long.parseLong(parts[1]); }
            catch (NumberFormatException e) { return; }

            String sender = messageSenders.get(id);
            if (sender == null) return;

            ClientHandler senderH = clients.get(sender);
            if (senderH != null && !sender.equals(username)) {
                senderH.send("DELIVERED " + id + " " + username);
            }
        }

        // /channels
        private void listChannels() {
            if (!ensureLogin()) return;

            send("CHANNELS_BEGIN");
            for (Channel ch : channels.values()) {
                String locked = (ch.password == null) ? "open" : "locked";
                send("CHANNEL " + ch.name + " " + locked + " owner=" + ch.owner + " members=" + ch.members.size());
            }
            send("CHANNELS_END");
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

        // /leave <channel>
        private void leaveChannel(String[] parts) {
            if (!ensureLogin()) return;
            if (parts.length < 2) { send("ERR Usage: /leave <channel>"); return; }

            String channel = parts[1];
            Channel ch = channels.get(channel);
            if (ch == null) { send("ERR Channel not found: " + channel); return; }

            if (!ch.members.contains(this)) { send("ERR You are not in channel: " + channel); return; }

            ch.members.remove(this);
            send("OK LEAVE " + channel);
            broadcastToChannel(ch, "INFO [" + channel + "] " + username + " left.");
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
            messages.put(id, new MessageRecord(id, MsgType.CHANNEL, username, null, channel));

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
            if (!auth.containsKey(to)) { send("ERR No such user: " + to); return; }
            if (!clients.containsKey(to)) { send("ERR User is offline: " + to); return; }

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
            if (target == null || !ch.members.contains(target)) { send("ERR User not in channel."); return; }

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

            // remove messages that belonged to this channel (cleanup)
            for (Map.Entry<Long, MessageRecord> e : messages.entrySet()) {
                MessageRecord r = e.getValue();
                if (r.type == MsgType.CHANNEL && channel.equals(r.channel)) {
                    messages.remove(e.getKey());
                    messageSenders.remove(e.getKey());
                }
            }

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
    }
}
