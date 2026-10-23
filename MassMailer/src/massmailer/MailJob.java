package massmailer;

import java.time.Instant;

/** One recipient-specific email in the in-memory queue. */
final class MailJob {
    enum Status { PENDING, PROCESSING, SENT, SIMULATED, FAILED }
    final String recipient;
    final String subject;
    final String body;
    final Instant created = Instant.now();
    volatile Status status = Status.PENDING;
    volatile int attempts;
    volatile String detail = "Đang chờ";
    volatile Instant completed;

    MailJob(String recipient, String subject, String body) {
        this.recipient = recipient;
        this.subject = subject;
        this.body = body;
    }
}
