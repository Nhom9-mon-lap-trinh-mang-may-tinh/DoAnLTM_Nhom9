package massmailer;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Minimal SMTP client using only the JDK (SSL/TLS and STARTTLS supported). */
final class SmtpClient {
    record Settings(String host, int port, String username, String password,
                    String from, boolean implicitTls, boolean startTls) { }
    static final class SmtpException extends IOException {
        final boolean transientFailure;
        SmtpException(String message, boolean transientFailure) {
            super(message); this.transientFailure = transientFailure;
        }
    }
    private SmtpClient() { }

    static void send(Settings s, MailJob job) throws IOException {
        Socket socket = null;
        SSLSocketFactory sslFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        try {
            socket = s.implicitTls()
                    ? sslFactory.createSocket(s.host(), s.port())
                    : new Socket(s.host(), s.port());
            socket.setSoTimeout(20000);
            socket.setTcpNoDelay(true);
            BufferedReader in = reader(socket);
            BufferedWriter out = writer(socket);
            expect(in, 220, "SMTP greeting");
            command(in, out, "EHLO massmailer.local", 250);
            if (!s.implicitTls() && s.startTls()) {
                command(in, out, "STARTTLS", 220);
                SSLSocket tls = (SSLSocket) sslFactory.createSocket(socket, s.host(), s.port(), true);
                tls.setSoTimeout(20000);
                tls.startHandshake();
                socket = tls;
                in = reader(socket); out = writer(socket);
                command(in, out, "EHLO massmailer.local", 250);
            }
            if (s.username() != null && !s.username().isBlank()) {
                command(in, out, "AUTH LOGIN", 334);
                command(in, out, Base64.getEncoder().encodeToString(s.username().getBytes(StandardCharsets.UTF_8)), 334);
                String authPassword = s.password();
                if (s.host().equalsIgnoreCase("smtp.gmail.com")) authPassword = authPassword.replaceAll("\\s+", "");
                command(in, out, Base64.getEncoder().encodeToString(authPassword.getBytes(StandardCharsets.UTF_8)), 235);
            }
            command(in, out, "MAIL FROM:<" + cleanAddress(s.from()) + ">", 250);
            command(in, out, "RCPT TO:<" + cleanAddress(job.recipient) + ">", 250, 251);
            command(in, out, "DATA", 354);
            String message = "From: <" + cleanAddress(s.from()) + ">\r\n"
                    + "To: <" + cleanAddress(job.recipient) + ">\r\n"
                    + "Subject: =?UTF-8?B?" + Base64.getEncoder().encodeToString(job.subject.getBytes(StandardCharsets.UTF_8)) + "?=\r\n"
                    + "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n"
                    + "Content-Transfer-Encoding: base64\r\n\r\n"
                    + Base64.getMimeEncoder( MIME_LINE_LENGTH, new byte[]{'\r','\n'}).encodeToString(job.body.getBytes(StandardCharsets.UTF_8))
                    .replace("\r\n", "\r\n") + "\r\n.\r\n";
            out.write(message); out.flush();
            expect(in, 250, "Message acceptance");
            command(in, out, "QUIT", 221);
        } finally {
            if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        }
    }
    private static final int MIME_LINE_LENGTH = 76;
    private static String cleanAddress(String value) {
        String v = value.trim();
        if (v.contains("\r") || v.contains("\n") || v.contains("<") || v.contains(">"))
            throw new IllegalArgumentException("Địa chỉ email không hợp lệ");
        return v;
    }
    private static BufferedReader reader(Socket s) throws IOException {
        return new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
    }
    private static BufferedWriter writer(Socket s) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
    }
    private static void command(BufferedReader in, BufferedWriter out, String text, int... expected) throws IOException {
        out.write(text); out.write("\r\n"); out.flush(); expect(in, expected, text.replaceAll("(?i)(AUTH LOGIN|[A-Za-z0-9+/]{20,})", "[ẩn]"));
    }
    private static void expect(BufferedReader in, int code, String step) throws IOException { expect(in, new int[]{code}, step); }
    private static void expect(BufferedReader in, int[] codes, String step) throws IOException {
        String line; int response = -1; StringBuilder detail = new StringBuilder();
        do {
            line = in.readLine();
            if (line == null) throw new IOException("SMTP đóng kết nối tại bước " + step);
            if (line.length() >= 3) try { response = Integer.parseInt(line.substring(0, 3)); } catch (NumberFormatException ignored) { }
            if (detail.length() < 300) detail.append(line).append(' ');
        } while (line.length() > 3 && line.charAt(3) == '-');
        for (int code : codes) if (response == code) return;
        boolean temporary = response >= 400 && response < 500;
        throw new SmtpException("SMTP " + response + " tại " + step + ": " + detail, temporary);
    }
}
