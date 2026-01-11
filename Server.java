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
    private static final AtomicLong pollIdGen = new AtomicLong(1);

    // pollId -> Poll (global lookup for pollId-based commands)
    private static final Map<Long, Poll> polls = new ConcurrentHashMap<>();

    // Auth
    private static class AuthRecord {
        final byte[] salt;
        final byte[] hash;
        AuthRecord(byte[] salt, byte[] hash) { this.salt = salt; this.hash = hash; }
    }

    // Computes a SHA-256 hash of the given byte array.
    // This is used to securely hash sensitive data such as passwords.
    private static byte[] sha256(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(input);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    // Creates an authentication record for a password.
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

    // Check if it is verified or not
    private static boolean verifyAuth(AuthRecord rec, String password) {
        byte[] passBytes = password.getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[rec.salt.length + passBytes.length];
        System.arraycopy(rec.salt, 0, combined, 0, rec.salt.length);
        System.arraycopy(passBytes, 0, combined, rec.salt.length, passBytes.length);
        byte[] hash = sha256(combined);
        return Arrays.equals(hash, rec.hash);
    }

    // Poll 
    private static class Poll {
        final long pollId;
        final String channelName;     // where it was created
        final String creator;         // username
        final String title;
        final List<String> options;   // index 0..n-1
        final Map<String, Integer> votesByUser = new ConcurrentHashMap<>(); // username -> optionIndex
        volatile boolean closed = false;

        Poll(long pollId, String channelName, String creator, String title, List<String> options) {
            this.pollId = pollId;
            this.channelName = channelName;
            this.creator = creator;
            this.title = title;
            this.options = options;
        }
        // Count votes for each option index
        int[] tally() {
            int[] counts = new int[options.size()];
            for (Integer idx : votesByUser.values()) {
                if (idx != null && idx >= 0 && idx < counts.length) counts[idx]++;
            }
            return counts;
        }
        // Format options as 1:optA | 2:optB |...
        String formatOptionsLine() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < options.size(); i++) {
                if (i > 0) sb.append(" | ");
                sb.append(i + 1).append(":").append(options.get(i));
            }
            return sb.toString();
        }
    }

    // Channel
    private static class Channel {
        final String name;
        final String owner;
        final String password; // null = none
        final CopyOnWriteArraySet<ClientHandler> members = new CopyOnWriteArraySet<>();
        volatile Long activePollId = null; // pollId of current active poll (null if none)

        Channel(String name, String owner, String password) {
            this.name = name;
            this.owner = owner;
            this.password = (password == null || password.isBlank()) ? null : password;
        }
    }

    // Message record
    private enum MsgType { DM, CHANNEL }
    // Minimal message metadata stored for delete operations
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
        // Require port argument
        if (args.length != 1) {
            System.out.println("Usage: java Server <port>");
            return;
        }
        int port = Integer.parseInt(args[0]);
        // Accept connections forever; each client handled in its own thread
        try (ServerSocket ss = new ServerSocket(port)) {
            System.out.println("Server running on port " + port);
            while (true) {
                Socket s = ss.accept();
                new Thread(new ClientHandler(s)).start();
            }
        } catch (java.net.BindException be) {
            // Common error: port already in use
            System.out.println("Server error: Address already in use (port " + port + ")");
            System.out.println("Fix:");
            System.out.println("  lsof -nP -iTCP:" + port + " -sTCP:LISTEN");
            System.out.println("  kill <PID>");
        } catch (IOException e) {
            System.out.println("Server error:");
            e.printStackTrace();
        }
    }
    // Send a line to all current channel members
    private static void broadcastToChannel(Channel ch, String line) {
        for (ClientHandler m : ch.members) m.send(line);
    }
    // Send a line to a specific online user (if present)
    private static void sendToUser(String username, String line) {
        ClientHandler h = clients.get(username);
        if (h != null) h.send(line);
    }
    // Remove a client handler from every channel membership set
    private static void removeFromAllChannels(ClientHandler h) {
        for (Channel c : channels.values()) c.members.remove(h);
    }
    // Handles one connected client socket
    private static class ClientHandler implements Runnable {
        private final Socket socket;
        private BufferedReader in;
        private PrintWriter out;
        private String username;              // logged-in user
        private String activeChannel = null;  // last joined channel (required for poll commands)

        ClientHandler(Socket socket) { this.socket = socket; }
        // Send one protocol line to this client
        void send(String line) {
            if (out != null) out.println(line);
        }

        @Override
        public void run() {
            try {
                // Setup text-based protocol streams (UTF-8)
                in  = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                // Print help banner on connect
                send("INFO Welcome.\n");
                send("INFO Auth: /register <user> <pass> then /login <user> <pass>\n");
                send("INFO Messaging: /msg <target> <message> (target=user => DM, target=channel => channel)\n");
                send("INFO Poll: /createpoll <title> <opt1|opt2|...>\n");
                send("INFO       /answerpoll <pollId> <choice>  | /pollresults <pollId> | /closepoll <pollId>\n");
                send("INFO Commands: /channels /createchannel /join /leave /mychannels /sendfile /delete /delivered /kick /deletechannel /quit\n");

                // Main receive loop: commands only (lines starting with '/')
                String line;
                while ((line = in.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    if (line.startsWith("/")) handleCommand(line);
                    else {
                        send("ERR Plain text sending is disabled.");
                        send("ERR Use /msg <channelname> <message> or /msg <username> <message>.");
                    }
                }
            } catch (IOException ignored) {
            } finally {
                if (username != null) clients.remove(username);
                removeFromAllChannels(this);
                try { socket.close(); } catch (IOException ignored) {}
            }
        }

        // Guard: require login before executing most commands
        private boolean ensureLogin() {
            if (username == null) {
                send("ERR You must /login first.");
                return false;
            }
            return true;
        }

        // Parse command and dispatch to feature handlers
        private void handleCommand(String line) {
            // default split (up to 3 parts)
            String[] parts3 = line.split("\\s+", 3);
            String cmd = parts3[0];

            switch (cmd) {
                // auth
                case "/register": register(parts3); break;
                case "/login": login(parts3); break;

                // messaging
                case "/msg": msgToUserOrChannel(parts3); break;
                case "/delete": deleteMessage(parts3); break;
                case "/delivered": delivered(parts3); break;

                // poll (need special parsing)
                case "/createpoll": createPoll(parts3); break;

                case "/answerpoll": {
                    // /answerpoll <pollId> <choice...>
                    String[] p = line.split("\\s+", 3);
                    answerPollById(p);
                    break;
                }
                case "/pollresults": {
                    // /pollresults <pollId>
                    String[] p = line.split("\\s+", 2);
                    pollResultsById(p);
                    break;
                }
                case "/closepoll": {
                    // /closepoll <pollId>
                    String[] p = line.split("\\s+", 2);
                    closePollById(p);
                    break;
                }

                // channels
                case "/channels": listChannels(); break;
                case "/createchannel": createChannel(line); break;
                case "/join": join(line); break;
                case "/leave": leaveChannel(parts3); break;
                case "/mychannels": myChannels(); break;

                // file
                case "/sendfile": sendFile(parts3); break;

                // admin
                case "/kick": kick(parts3); break;
                case "/deletechannel": deleteChannel(parts3); break;

                case "/quit":
                    send("INFO Bye!");
                    try { socket.close(); } catch (IOException ignored) {}
                    break;

                default:
                    send("ERR Unknown command: " + cmd);
            }
        }

        // /register <user> <pass>
        //// Register a new user with salted password hash
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
        // Login: verify password + ensure single active session per user
        private void login(String[] parts) {
            if (parts.length < 3) { send("ERR Usage: /login <user> <pass>"); return; }
            if (username != null) { send("ERR Already logged in as " + username); return; }

            String user = parts[1].trim();
            String pass = parts[2];

            AuthRecord rec = auth.get(user);
            if (rec == null) { send("ERR NO_SUCH_USER"); return; }
            if (!verifyAuth(rec, pass)) { send("ERR WRONG_PASSWORD"); return; }

            // Prevent duplicate logins for the same username
            if (clients.putIfAbsent(user, this) != null) {
                send("ERR USER_ALREADY_ONLINE");
                return;
            }

            username = user;
            send("OK LOGIN " + username);
        }

        
        // /msg <target> <message>
        // - target matches channel name => channel post (member required)
        // - otherwise => DM (user must exist + be online)
        // Send message either to a channel (if target matches) or to a user (DM)
        private void msgToUserOrChannel(String[] parts) {
            if (!ensureLogin()) return;
            if (parts.length < 3) { send("ERR Usage: /msg <target> <message>"); return; }

            String target = parts[1];
            String msg = parts[2];

            Channel ch = channels.get(target);
            if (ch != null) {
                // Require membership to post
                if (!ch.members.contains(this)) {
                    send("ERR You are not in channel: " + target);
                    return;
                }

                // Allocate messageId + store metadata for delete/receipt features
                long id = msgId.getAndIncrement();
                messageSenders.put(id, username);
                messages.put(id, new MessageRecord(id, MsgType.CHANNEL, username, null, target));

                // Broadcast to all members
                broadcastToChannel(ch, "CHANNELMSG " + id + " " + target + " " + username + " " + msg);

                // Mention detection: notify mentioned users
                for (ClientHandler m : ch.members) {
                    if (m.username == null) continue;
                    if (!m.username.equals(username) && msg.contains("@" + m.username)) {
                        m.send("MENTION " + id + " " + target + " " + username + " " + msg);
                    }
                }
                return;
            }

            // DM path: require user exists and is online
            if (!auth.containsKey(target)) { send("ERR No such user: " + target); return; }
            if (!clients.containsKey(target)) { send("ERR User is offline: " + target); return; }

            long id = msgId.getAndIncrement();
            messageSenders.put(id, username);
            messages.put(id, new MessageRecord(id, MsgType.DM, username, target, null));

            // Send to recipient + echo back to sender
            sendToUser(target, "MSG " + id + " " + username + " " + msg);
            send("MSG " + id + " " + username + " (to " + target + ") " + msg);

            if (msg.contains("@" + target)) {
                sendToUser(target, "MENTION " + id + " DM " + username + " " + msg);
            }
        }

        // /delete <messageId>
        // Delete message if sender (DM) or sender/owner (channel)
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
                allowed = rec.from.equals(username);
            } else {
                Channel ch = channels.get(rec.channel);
                if (ch != null) allowed = rec.from.equals(username) || ch.owner.equals(username);
            }

            if (!allowed) { send("ERR Not allowed to delete this message"); return; }

            // Remove from server-side indexes
            messages.remove(id);
            messageSenders.remove(id);
            // Notify recipients so clients can hide the message
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
        // Read receipt: notify original sender that this user received the message
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

        // /createpoll <title> <opt1|opt2|...> 
        // Create a new poll in the current active channel 
        private void createPoll(String[] parts) {
            if (!ensureLogin()) return;
            // Require: title + option list, and a joined channel context
            if (parts.length < 3) {
                send("ERR Usage: /createpoll <title> <opt1|opt2|...>");
                send("ERR Join a channel first: /join <channel> [password]");
                return;
            }
            if (activeChannel == null) {
                send("ERR You must /join a channel first. (Polls are channel-only)");
                return;
            }

            Channel ch = channels.get(activeChannel);
            if (ch == null) { send("ERR Active channel not found: " + activeChannel); return; }
            if (!ch.members.contains(this)) { send("ERR You are not in channel: " + activeChannel); return; }

            String title = parts[1];
            String optRaw = parts[2];

            List<String> options = parseOptions(optRaw);
            if (options.size() < 2) {
                send("ERR Poll must have at least 2 options. Use: opt1|opt2|...");
                return;
            }
            if (options.size() > 10) {
                send("ERR Too many options (max 10).");
                return;
            }
            // Create poll + mark as active for the channel
            long pid = pollIdGen.getAndIncrement();
            Poll poll = new Poll(pid, ch.name, username, title, options);

            polls.put(pid, poll);
            ch.activePollId = pid;

            broadcastToChannel(ch, "POLL_CREATED " + ch.name + " " + pid + " " + title);
            broadcastToChannel(ch, "POLL_OPTIONS " + ch.name + " " + poll.formatOptionsLine());
            broadcastToChannel(ch, "POLL_HOWTO " + ch.name + " Use: /answerpoll " + pid + " <choice>");
        }

        // /answerpoll <pollId> <choice>
        // Cast/update a vote by pollId
        private void answerPollById(String[] parts) {
            if (!ensureLogin()) return;
            if (parts.length < 3) {
                send("ERR Usage: /answerpoll <pollId> <choice>");
                return;
            }

            Long pid = parseLong(parts[1]);
            if (pid == null) { send("ERR Invalid pollId"); return; }

            Poll poll = polls.get(pid);
            if (poll == null) { send("ERR No such pollId: " + pid); return; }

            Channel ch = channels.get(poll.channelName);
            if (ch == null) { send("ERR Poll channel no longer exists: " + poll.channelName); return; }

            // Enforce: must be in the SAME channel context + membership
            if (activeChannel == null || !activeChannel.equals(poll.channelName)) {
                send("ERR You must /join the poll channel first: " + poll.channelName);
                return;
            }
            if (!ch.members.contains(this)) {
                send("ERR You are not a member of channel: " + poll.channelName);
                return;
            }

            // Enforce active & open poll
            if (poll.closed) {
                send("ERR Poll is closed: " + pid);
                return;
            }
            if (ch.activePollId == null || !ch.activePollId.equals(pid)) {
                send("ERR Poll is not active in #" + ch.name + ": " + pid);
                return;
            }
            // Resolve choice by number or exact option text
            String choice = parts[2];
            Integer idx = resolveChoiceIndex(poll, choice);
            if (idx == null) {
                send("ERR Invalid choice. Use number 1.." + poll.options.size() + " or option text.");
                send("ERR Options: " + poll.formatOptionsLine());
                return;
            }
            // Save vote (overwrites previous vote from same user)
            poll.votesByUser.put(username, idx);

            // Broadcast vote + updated tally
            broadcastToChannel(ch, "POLL_VOTE " + ch.name + " " + poll.pollId + " " + username + " voted " + (idx + 1) + ":" + poll.options.get(idx));

            int[] counts = poll.tally();
            broadcastToChannel(ch, "POLL_TALLY " + ch.name + " " + poll.pollId + " " + formatTallyLine(poll, counts));
        }

        // /pollresults <pollId>
        // Show current results (broadcast to channel)
        private void pollResultsById(String[] parts) {
            if (!ensureLogin()) return;
            if (parts.length < 2) { send("ERR Usage: /pollresults <pollId>"); return; }

            Long pid = parseLong(parts[1]);
            if (pid == null) { send("ERR Invalid pollId"); return; }

            Poll poll = polls.get(pid);
            if (poll == null) { send("ERR No such pollId: " + pid); return; }

            Channel ch = channels.get(poll.channelName);
            if (ch == null) { send("ERR Poll channel no longer exists: " + poll.channelName); return; }

            // Must be in same channel context + membership
            if (activeChannel == null || !activeChannel.equals(poll.channelName)) {
                send("ERR You must /join the poll channel first: " + poll.channelName);
                return;
            }
            if (!ch.members.contains(this)) {
                send("ERR You are not a member of channel: " + poll.channelName);
                return;
            }

            int[] counts = poll.tally();
            // Broadcast a snapshot of poll state
            broadcastToChannel(ch, "POLL_RESULTS " + ch.name + " " + poll.pollId + " " + poll.title + (poll.closed ? " [CLOSED]" : ""));
            broadcastToChannel(ch, "POLL_OPTIONS " + ch.name + " " + poll.formatOptionsLine());
            broadcastToChannel(ch, "POLL_TALLY " + ch.name + " " + poll.pollId + " " + formatTallyLine(poll, counts));
        }

        // /closepoll <pollId>
        private void closePollById(String[] parts) {
            if (!ensureLogin()) return;
            if (parts.length < 2) { send("ERR Usage: /closepoll <pollId>"); return; }

            Long pid = parseLong(parts[1]);
            if (pid == null) { send("ERR Invalid pollId"); return; }

            Poll poll = polls.get(pid);
            if (poll == null) { send("ERR No such pollId: " + pid); return; }

            Channel ch = channels.get(poll.channelName);
            if (ch == null) { send("ERR Poll channel no longer exists: " + poll.channelName); return; }

            // Must be in same channel context + membership
            if (activeChannel == null || !activeChannel.equals(poll.channelName)) {
                send("ERR You must /join the poll channel first: " + poll.channelName);
                return;
            }
            if (!ch.members.contains(this)) {
                send("ERR You are not a member of channel: " + poll.channelName);
                return;
            }

            // Permission: poll creator OR channel owner
            if (!username.equals(poll.creator) && !username.equals(ch.owner)) {
                send("ERR Only poll creator or channel owner can close the poll.");
                return;
            }

            if (poll.closed) {
                send("ERR Poll already closed: " + pid);
                return;
            }

            poll.closed = true;
            if (ch.activePollId != null && ch.activePollId.equals(pid)) ch.activePollId = null;

            int[] counts = poll.tally();
            // Broadcast final results
            broadcastToChannel(ch, "POLL_CLOSED " + ch.name + " " + poll.pollId + " " + poll.title);
            broadcastToChannel(ch, "POLL_OPTIONS " + ch.name + " " + poll.formatOptionsLine());
            broadcastToChannel(ch, "POLL_TALLY " + ch.name + " " + poll.pollId + " " + formatTallyLine(poll, counts));
        }

        private List<String> parseOptions(String optRaw) {
            String[] parts = optRaw.split("\\|");
            List<String> opts = new ArrayList<>();
            for (String p : parts) {
                String s = p.trim();
                if (!s.isEmpty()) opts.add(s);
            }
            return opts;
        }

        // Resolve a vote choice by number ("1") or exact option text
        private Integer resolveChoiceIndex(Poll poll, String choice) {
            try {
                int n = Integer.parseInt(choice.trim());
                if (n >= 1 && n <= poll.options.size()) return n - 1;
            } catch (NumberFormatException ignored) {}

            String c = choice.trim().toLowerCase(Locale.ROOT);
            for (int i = 0; i < poll.options.size(); i++) {
                if (poll.options.get(i).trim().toLowerCase(Locale.ROOT).equals(c)) return i;
            }
            return null;
        }

        // Format tally as "1:optA=3 | 2:optB=1 | ..."
        private String formatTallyLine(Poll poll, int[] counts) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < poll.options.size(); i++) {
                if (i > 0) sb.append(" | ");
                sb.append(i + 1).append(":").append(poll.options.get(i)).append("=").append(counts[i]);
            }
            return sb.toString();
        }

        // Safe long parsing helper
        private Long parseLong(String s) {
            try { return Long.parseLong(s.trim()); }
            catch (Exception e) { return null; }
        }

        // /channels
        // List all channels with lock status and current member count
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
        // Create channel and auto-join the creator
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

            activeChannel = name;
            send("OK CREATECHANNEL " + name + " owner=" + username);
            send("OK JOIN " + name);
        }

        // /join <channel> [password]
        // Join an existing channel (password if locked)
        private void join(String line) {
            if (!ensureLogin()) return;
            String[] t = line.split("\\s+", 3);
            if (t.length < 2) { send("ERR Usage: /join <channel> [password]"); return; }

            String channel = t[1];
            String pass = (t.length == 3) ? t[2] : null;

            Channel ch = channels.get(channel);
            if (ch == null) { send("ERR Channel does not exist. Use /createchannel."); return; }

            // Enforce channel password when locked
            if (ch.password != null) {
                if (pass == null || !ch.password.equals(pass)) {
                    send("ERR Wrong password for channel " + channel);
                    return;
                }
            }

            ch.members.add(this);
            activeChannel = channel;
            send("OK JOIN " + channel);
            broadcastToChannel(ch, "INFO [" + channel + "] " + username + " joined.");

            // If an active poll exists, show it (pollId-based)
            if (ch.activePollId != null) {
                Poll p = polls.get(ch.activePollId);
                if (p != null) {
                    send("POLL_ACTIVE " + ch.name + " " + p.pollId + " " + p.title);
                    send("POLL_OPTIONS " + ch.name + " " + p.formatOptionsLine());
                    send("POLL_HOWTO " + ch.name + " Use: /answerpoll " + p.pollId + " <choice>");
                }
            }
        }

        // /leave <channel>
        // Leave a channel and clear activeChannel if needed
        private void leaveChannel(String[] parts) {
            if (!ensureLogin()) return;
            if (parts.length < 2) { send("ERR Usage: /leave <channel>"); return; }

            String channel = parts[1];
            Channel ch = channels.get(channel);
            if (ch == null) { send("ERR Channel not found: " + channel); return; }
            if (!ch.members.contains(this)) { send("ERR You are not in channel: " + channel); return; }

            ch.members.remove(this);
            if (channel.equals(activeChannel)) activeChannel = null;
            send("OK LEAVE " + channel);
            broadcastToChannel(ch, "INFO [" + channel + "] " + username + " left.");
        }

        // /mychannels
        // List channels that this user has joined (plus active channel)
        private void myChannels() {
            if (!ensureLogin()) return;
            StringBuilder sb = new StringBuilder("MYCHANNELS");
            for (Channel ch : channels.values()) {
                if (ch.members.contains(this)) sb.append(" ").append(ch.name);
            }
            send(sb.toString());
            send("INFO ActiveChannel=" + (activeChannel == null ? "(none)" : activeChannel));
        }

        // /sendfile <user> <filename> <base64>
        // Send a file as base64 over the text protocol (DM only)
        private void sendFile(String[] parts) {
            if (!ensureLogin()) return;
            if (parts.length < 3) { send("ERR Usage: /sendfile <user> <filename> <base64>"); return; }

            String to = parts[1];
             // Require user exists and is online
            if (!auth.containsKey(to)) { send("ERR No such user: " + to); return; }
            if (!clients.containsKey(to)) { send("ERR User is offline: " + to); return; }

            // Parse "<filename> <base64...>" from the remaining string
            String rest = parts[2];
            int sp = rest.indexOf(' ');
            if (sp <= 0) { send("ERR Usage: /sendfile <user> <filename> <base64>"); return; }

            String filename = rest.substring(0, sp);
            String b64 = rest.substring(sp + 1);

            try { Base64.getDecoder().decode(b64); }
            catch (IllegalArgumentException e) { send("ERR Invalid base64"); return; }

            // Forward payload to recipient
            sendToUser(to, "FILE_FROM " + username + " " + filename + " " + b64);
            send("INFO File sent to " + to + ": " + filename);
        }

        // /kick <channel> <user>
        // Remove a user from a channel (owner only)
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
            if (channel.equals(target.activeChannel)) target.activeChannel = null;

            target.send("INFO You were removed from #" + channel + " by " + username);
            broadcastToChannel(ch, "INFO [" + channel + "] " + targetUser + " was removed by " + username);
        }

        // /deletechannel <channel>
        // Delete a channel and purge related channel messages (owner only)
        private void deleteChannel(String[] parts) {
            if (!ensureLogin()) return;
            if (parts.length < 2) { send("ERR Usage: /deletechannel <channel>"); return; }

            String channel = parts[1];
            Channel ch = channels.get(channel);
            if (ch == null) { send("ERR Channel not found: " + channel); return; }
            if (!ch.owner.equals(username)) { send("ERR Only owner can delete."); return; }

            // Notify members, then remove channel
            broadcastToChannel(ch, "INFO Channel #" + channel + " was deleted by " + username);
            channels.remove(channel);
            // Remove stored message metadata for this channel
            for (Map.Entry<Long, MessageRecord> e : new ArrayList<>(messages.entrySet())) {
                MessageRecord r = e.getValue();
                if (r.type == MsgType.CHANNEL && channel.equals(r.channel)) {
                    messages.remove(e.getKey());
                    messageSenders.remove(e.getKey());
                }
            }
            // Clear activeChannel for clients who were focused on this channel
            for (ClientHandler h : clients.values()) {
                if (channel.equals(h.activeChannel)) h.activeChannel = null;
            }

            send("OK DELETECHANNEL " + channel);
        }
    }
}

