# NSFinalProject
Command Line-based Chat Application (GroupL)
A Java-based command-line chat application utilizing TCP socket communication. This system provides a robust environment for personal and group interactions, featuring direct messaging, password-protected channels, file transfers, and a built-in polling system.

1. Project Overview
This application follows a client-server architecture where multiple clients communicate through a central server. Inspired by the IRC (Internet Relay Chat) model, it uses a custom text-based protocol to ensure structured routing and efficient server-side processing.

2. Key Features
💬 Communication Models

Direct Messaging (DM): One-to-one private messaging with unique message IDs.

Channels: Group communication with support for password protection.

Mentions: Notify specific users within a channel by including their username.

📁 File Exchange

Secure Byte Stream Transfer: Files are transmitted over TCP and automatically saved on the recipient's device, ensuring reliable data delivery.

📊 Interactive Tools

Polling System: Create and vote on polls within channels to support group decision-making.

Read Receipts: Real-time delivery confirmations for both individual and group messages.

🛡️ Administration & Security

User Management: Registration, login/logout, and prevention of concurrent duplicate logins.

Channel Moderation: Owners can remove members, delete messages, or close the entire channel.

3. System Architecture
Multi-threaded Design

Server: Employs a worker-thread model where each client connection is handled independently. Shared resources are protected via synchronization mechanisms to prevent race conditions.

Client: Uses a dual-thread approach (one for handling user input and another for listening to server responses) to ensure a non-blocking, real-time experience.

Communication Protocol

The system uses a custom command-based structure. Every message follows a COMMAND <ARGUMENTS> format terminated by a newline.

Command Example	Description
REGISTER <user> <pass>	Register a new account.
LOGIN <user> <pass>	Log into the system.
SEND <target> <message>	Send a DM to a specific user.
JOIN <channel> [pass]	Join a channel (with optional password).
FILE_SEND <user> <file>	Initiate a file transfer.
POLL_CREATE <q> <o1> <o2>	Start a poll in the current channel.
4. Technical Stack
Language: Java

Networking: Java Standard Library (java.net, TCP Sockets)

Concurrency: Java Multithreading (Thread, Runnable, synchronized blocks)

Environment: Command Line Interface (CLI)

5. Getting Started
Ensure you have Java 11 or higher installed.

Clone the Repository

Bash
git clone https://github.com/your-repo/command-line-chat.git
cd command-line-chat
Compile & Run the Server

Bash
javac Server.java
java Server
Compile & Run the Client (New Terminal)

Bash
javac Client.java
java Client

6. Contributors (GroupL)
FUJITA Ryusei (2600240348-9)
HORI Kosei (2600240366-7)
HNIN Ei Shwe Yee (2600240460-4)
