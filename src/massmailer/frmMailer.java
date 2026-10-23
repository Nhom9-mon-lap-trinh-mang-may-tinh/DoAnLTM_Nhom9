package massmailer;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/** Single-user desktop client with an in-memory FIFO and one rate-limited worker. */
public class frmMailer extends JFrame {
    private static final Logger LOG = Logger.getLogger(frmMailer.class.getName());
    private final JobTableModel tableModel = new JobTableModel();
    private final BlockingQueue<MailJob> queue = new LinkedBlockingQueue<>();
    private final List<MailJob> allJobs = new CopyOnWriteArrayList<>();
    private final ExecutorService workerPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "massmailer-worker"); t.setDaemon(true); return t;
    });
    private volatile boolean workerRunning;
    private volatile SmtpClient.Settings activeSettings;
    private volatile int maxPerMinute = 30;
    private volatile int maxRetries = 3;
    private volatile long lastAttemptNanos;
    private volatile long startedNanos;

    public frmMailer() {
        initComponents();
jobTable.setModel(tableModel);
        jobTable.setFillsViewportHeight(true);
        jobTable.setAutoCreateRowSorter(true);
        btnCsv.addActionListener(e -> loadCsv());
        btnClear.addActionListener(e -> recipients.setText(""));
        btnEnqueue.addActionListener(e -> enqueueJobs());
        setMinimumSize(new Dimension(850, 720));
        pack();
        setLocationRelativeTo(null);
        javax.swing.Timer timer = new javax.swing.Timer(800, e -> refresh()); timer.start();
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) { workerPool.shutdownNow(); }
        });
    }

    private void loadCsv() {
        JFileChooser chooser = new JFileChooser(); chooser.setDialogTitle("Chọn CSV (địa chỉ email ở cột đầu tiên)");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            List<String> values = new ArrayList<>();
            for (String line : Files.readAllLines(chooser.getSelectedFile().toPath(), StandardCharsets.UTF_8)) {
                String first = line.split(",", 2)[0].trim().replaceAll("^\"|\"$", "");
                if (!first.isBlank() && !first.equalsIgnoreCase("email") && !first.equalsIgnoreCase("recipient")) values.add(first);
            }
            recipients.setText(String.join("\n", values)); activity.setText("Đã tải " + values.size() + " địa chỉ từ CSV");
        } catch (IOException ex) { showError("Không đọc được CSV: " + ex.getMessage()); }
    }

    private void enqueueJobs() {
        final SmtpClient.Settings settings;
        final int configuredRate, configuredRetries;
        try {
            String h=host.getText().trim(), f=from.getText().trim(); int p=Integer.parseInt(port.getText().trim());
            configuredRate = Integer.parseInt(rate.getText().trim());
            configuredRetries = Integer.parseInt(retries.getText().trim());
            if (configuredRate < 1 || configuredRate > 10000 || configuredRetries < 0 || configuredRetries > 10)
                throw new IllegalArgumentException("Email/phút phải từ 1 đến 10000; số lần thử lại từ 0 đến 10.");
            if (h.isBlank() || p<1 || p>65535 || !validEmail(f)) throw new IllegalArgumentException("Kiểm tra máy chủ, cổng và địa chỉ gửi.");
            String smtpUser = username.getText().trim();
            String smtpPassword = new String(password.getPassword());
            if (!smtpUser.isBlank() && smtpPassword.isBlank())
                throw new IllegalArgumentException("Nhập mật khẩu SMTP; Gmail cần Mật khẩu ứng dụng, không phải mật khẩu đăng nhập Gmail.");
            settings = new SmtpClient.Settings(h,p,smtpUser,smtpPassword,f,implicitTls.isSelected(),startTls.isSelected());
        } catch (Exception ex) { showError("Cấu hình SMTP chưa hợp lệ: " + ex.getMessage()); return; }
        synchronized (this) {
            if (workerRunning && !settings.equals(activeSettings)) {
                showError("Hãy đợi hàng đợi hiện tại hoàn tất trước khi đổi cấu hình SMTP."); return;
            }
        }
        String title=subject.getText(); String text=body.getText();
        if(title.isBlank() || text.isBlank()) { showError("Nhập tiêu đề và nội dung email."); return; }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for(String token:recipients.getText().split("[\\s;,]+")) if(!token.isBlank()) unique.add(token.trim());
        if(unique.isEmpty()) { showError("Nhập ít nhất một địa chỉ người nhận."); return; }
        List<String> invalid=unique.stream().filter(a->!validEmail(a)).toList();
        if(!invalid.isEmpty()) { showError("Địa chỉ không hợp lệ: " + String.join(", ", invalid.stream().limit(5).toList())); return; }
        maxPerMinute=configuredRate; maxRetries=configuredRetries;
        if(allJobs.isEmpty()) startedNanos=System.nanoTime();
        for(String address:unique) { MailJob job=new MailJob(address,title,text); allJobs.add(job);queue.offer(job); }
        LOG.info(() -> "Đã đưa " + unique.size() + " công việc vào Queue RAM");
        activity.setText("Đã xếp " + unique.size() + " email; queue lưu trong RAM"); refresh();
        synchronized(this) { if(!workerRunning) { workerRunning=true; activeSettings=settings; workerPool.submit(() -> process(settings)); } }
    }

    private void process(SmtpClient.Settings settings) {
        try {
            MailJob job;
            while((job=queue.poll())!=null) {
                if (isSampleAddress(job.recipient)) {
                    job.status=MailJob.Status.SIMULATED;
                    job.detail="\u0110\u1ecba ch\u1ec9 m\u1eabu; kh\u00f4ng g\u1eedi qua SMTP";
                    job.completed=Instant.now();
                    refreshOnUi();
                    continue;
                }
                job.status=MailJob.Status.PROCESSING; job.detail="Đang xử lý"; refreshOnUi();
                boolean done=false;
                while(!done) {
                    waitForRate(); job.attempts++;
                    try {
                        LOG.info("Bắt đầu gửi tới " + job.recipient + ", lần thử " + job.attempts);
                        SmtpClient.send(settings,job); job.status=MailJob.Status.SENT;job.detail="Đã được SMTP chấp nhận";job.completed=Instant.now();done=true;
                        LOG.info("SMTP đã chấp nhận email tới " + job.recipient);
                    } catch(SmtpClient.SmtpException ex) {
                        LOG.log(ex.transientFailure ? Level.WARNING : Level.SEVERE,
                                "SMTP thất bại với " + job.recipient + ": " + ex.getMessage());
                        String failureDetail = ex.getMessage();
                        if (failureDetail.startsWith("SMTP 535") && settings.host().equalsIgnoreCase("smtp.gmail.com"))
                            failureDetail += " | Gmail: kiểm tra địa chỉ tài khoản và Mật khẩu ứng dụng (bật Xác minh 2 bước).";
                        if(ex.transientFailure && job.attempts<=maxRetries) {
                            job.detail="Lỗi tạm thời; sẽ thử lại: "+failureDetail; refreshOnUi();
                            sleepBackoff(job.attempts);
                        } else { job.status=MailJob.Status.FAILED;job.detail=failureDetail;job.completed=Instant.now();done=true; }
                    } catch(Exception ex) {
                        // Do not retry ambiguous transport failures: SMTP may have accepted DATA already.
                        LOG.log(Level.SEVERE, "Lỗi gửi tới " + job.recipient + "; không thử lại để tránh gửi trùng", ex);
                        job.status=MailJob.Status.FAILED;job.detail="Không tự thử lại để tránh gửi trùng: "+ex.getMessage();job.completed=Instant.now();done=true;
                    }
                    refreshOnUi();
                }
            }
        } finally {
            synchronized(this) { workerRunning=false; activeSettings=null; if(!queue.isEmpty()) { workerRunning=true; activeSettings=settings; workerPool.submit(() -> process(settings)); } }
            refreshOnUi();
        }
    }

    private void waitForRate() {
        long minute=TimeUnit.MINUTES.toNanos(1);
        long spacing=(minute+maxPerMinute-1)/maxPerMinute;
        long wait=lastAttemptNanos+spacing-System.nanoTime();
        if(wait>0) try { TimeUnit.NANOSECONDS.sleep(wait); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        lastAttemptNanos=System.nanoTime();
    }
    private static void sleepBackoff(int attempt) {
        try { Thread.sleep(Math.min(30000L,1000L << Math.min(attempt-1,5))); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    private void refreshOnUi() { SwingUtilities.invokeLater(this::refresh); }
    private void refresh() {
        tableModel.fireTableDataChanged();
        long pending=allJobs.stream().filter(j->j.status==MailJob.Status.PENDING).count();
        long processing=allJobs.stream().filter(j->j.status==MailJob.Status.PROCESSING).count();
        long sent=allJobs.stream().filter(j->j.status==MailJob.Status.SENT).count();
        long simulated=allJobs.stream().filter(j->j.status==MailJob.Status.SIMULATED).count();
        long failed=allJobs.stream().filter(j->j.status==MailJob.Status.FAILED).count();
        summary.setText("Ch\u1edd " + pending + " | \u0110ang g\u1eedi " + processing + " | Th\u00e0nh c\u00f4ng " + sent + " | M\u1eabu " + simulated + " | Th\u1ea5t b\u1ea1i " + failed);
        if(!workerRunning && pending==0 && processing==0 && !allJobs.isEmpty()) {
            long elapsed=Math.max(1,TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-startedNanos));
            activity.setText(String.format(Locale.ROOT,"Hoàn tất sau %d giây • trung bình %.2f email/giây",elapsed,(sent+failed+simulated)/(double)elapsed));
        }
    }
    private static boolean isSampleAddress(String address) {
        String domain=address.substring(address.lastIndexOf('@')+1).toLowerCase(Locale.ROOT);
        return Set.of("example.com", "example.net", "example.org").contains(domain)
                || domain.endsWith(".example") || domain.endsWith(".test") || domain.endsWith(".invalid");
    }
    private static boolean validEmail(String value) { return value!=null && value.length()<=254 && Pattern.matches("^[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+$",value); }
    private void showError(String message) { JOptionPane.showMessageDialog(this,message,"Dữ liệu chưa hợp lệ",JOptionPane.WARNING_MESSAGE); }

    private final class JobTableModel extends AbstractTableModel {
        private final String[] columns={"Người nhận","Tiêu đề","Trạng thái","Lần thử","Chi tiết"};
        @Override public int getRowCount(){return allJobs.size();}
        @Override public int getColumnCount(){return columns.length;}
        @Override public String getColumnName(int c){return columns[c];}
        @Override public Object getValueAt(int r,int c){MailJob j=allJobs.get(r);return switch(c){case 0->j.recipient;case 1->j.subject;case 2->j.status;case 3->j.attempts;default->j.detail;};}
    }
    
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) { }
            new frmMailer().setVisible(true);
        });
    }

    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        lblHost = new javax.swing.JLabel();
        host = new javax.swing.JTextField();
        lblPort = new javax.swing.JLabel();
        port = new javax.swing.JTextField();
        lblUsername = new javax.swing.JLabel();
        username = new javax.swing.JTextField();
        lblPassword = new javax.swing.JLabel();
        password = new javax.swing.JPasswordField();
        lblFrom = new javax.swing.JLabel();
        from = new javax.swing.JTextField();
        startTls = new javax.swing.JCheckBox();
        implicitTls = new javax.swing.JCheckBox();
        lblRate = new javax.swing.JLabel();
        rate = new javax.swing.JTextField();
        lblRetries = new javax.swing.JLabel();
        retries = new javax.swing.JTextField();
        lblRecipients = new javax.swing.JLabel();
        recipientsScroll = new javax.swing.JScrollPane();
        recipients = new javax.swing.JTextArea();
        btnCsv = new javax.swing.JButton();
        btnClear = new javax.swing.JButton();
        lblSubject = new javax.swing.JLabel();
        subject = new javax.swing.JTextField();
        lblBody = new javax.swing.JLabel();
        bodyScroll = new javax.swing.JScrollPane();
        body = new javax.swing.JTextArea();
        btnEnqueue = new javax.swing.JButton();
        summary = new javax.swing.JLabel();
        activity = new javax.swing.JLabel();
        jobsScroll = new javax.swing.JScrollPane();
        jobTable = new javax.swing.JTable();

        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        setTitle("MassMailer — gửi email có hàng đợi");

        lblHost.setText("Máy chủ SMTP");

        host.setText("smtp.gmail.com");

        lblPort.setText("Cổng");

        port.setText("587");

        lblUsername.setText("Tài khoản");

        lblPassword.setText("Mật khẩu ");

        lblFrom.setText("Địa chỉ gửi");

        startTls.setText("STARTTLS");
        startTls.setSelected(true);

        implicitTls.setText("TLS ngầm (465)");

        lblRate.setText("Email/phút");

        rate.setText("30");

        lblRetries.setText("Thử lại lỗi 4xx");

        retries.setText("3");

        lblRecipients.setText("Người nhận (mỗi dòng một email, hoặc tải CSV)");

        recipients.setLineWrap(true);
        recipients.setWrapStyleWord(true);
        recipients.setRows(5);
        recipients.setColumns(40);
        recipientsScroll.setViewportView(recipients);

        btnCsv.setText("Tải danh sách CSV…");

        btnClear.setText("Xóa người nhận");

        lblSubject.setText("Tiêu đề");

        subject.setColumns(40);

        lblBody.setText("Nội dung (văn bản thuần)");

        body.setLineWrap(true);
        body.setWrapStyleWord(true);
        body.setRows(8);
        body.setColumns(40);
        bodyScroll.setViewportView(body);

        btnEnqueue.setText("Đưa vào Queue và gửi");

        summary.setText("Chưa có email trong hàng đợi");

        activity.setText("Sẵn sàng");

        jobsScroll.setViewportView(jobTable);

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addGap(12, 12, 12)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(lblHost)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(host, javax.swing.GroupLayout.DEFAULT_SIZE, 440, Short.MAX_VALUE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(lblPort)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(port, javax.swing.GroupLayout.DEFAULT_SIZE, 120, 120))
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(lblUsername)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(username, javax.swing.GroupLayout.DEFAULT_SIZE, 409, Short.MAX_VALUE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(lblPassword)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(password, javax.swing.GroupLayout.DEFAULT_SIZE, 410, Short.MAX_VALUE))
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(lblFrom)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(from, javax.swing.GroupLayout.DEFAULT_SIZE, 500, Short.MAX_VALUE)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(startTls)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(implicitTls))
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(lblRate)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(rate, javax.swing.GroupLayout.PREFERRED_SIZE, 90, 90)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(lblRetries)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(retries, javax.swing.GroupLayout.PREFERRED_SIZE, 90, 90))
                    .addComponent(lblRecipients)
                    .addComponent(recipientsScroll, javax.swing.GroupLayout.DEFAULT_SIZE, 960, Short.MAX_VALUE)
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(btnCsv)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(btnClear))
                    .addComponent(lblSubject)
                    .addComponent(subject, javax.swing.GroupLayout.DEFAULT_SIZE, 960, Short.MAX_VALUE)
                    .addComponent(lblBody)
                    .addComponent(bodyScroll, javax.swing.GroupLayout.DEFAULT_SIZE, 960, Short.MAX_VALUE)
                    .addGroup(layout.createSequentialGroup()
                        .addComponent(btnEnqueue)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(summary)
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addComponent(activity))
                    .addComponent(jobsScroll, javax.swing.GroupLayout.DEFAULT_SIZE, 960, Short.MAX_VALUE))
                .addGap(12, 12, 12))
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addGap(12, 12, 12)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblHost)
                    .addComponent(host, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(lblPort)
                    .addComponent(port, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblUsername)
                    .addComponent(username, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(lblPassword)
                    .addComponent(password, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblFrom)
                    .addComponent(from, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(startTls)
                    .addComponent(implicitTls))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(lblRate)
                    .addComponent(rate, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(lblRetries)
                    .addComponent(retries, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addGap(10, 10, 10)
                .addComponent(lblRecipients)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(recipientsScroll, javax.swing.GroupLayout.DEFAULT_SIZE, 90, Short.MAX_VALUE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(btnCsv)
                    .addComponent(btnClear))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(lblSubject)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(subject, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(lblBody)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(bodyScroll, javax.swing.GroupLayout.DEFAULT_SIZE, 150, Short.MAX_VALUE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(btnEnqueue)
                    .addComponent(summary)
                    .addComponent(activity))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(jobsScroll, javax.swing.GroupLayout.DEFAULT_SIZE, 160, Short.MAX_VALUE)
                .addGap(12, 12, 12))
        );

        pack();
    }// </editor-fold>//GEN-END:initComponents

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JLabel activity;
    private javax.swing.JTextArea body;
    private javax.swing.JScrollPane bodyScroll;
    private javax.swing.JButton btnClear;
    private javax.swing.JButton btnCsv;
    private javax.swing.JButton btnEnqueue;
    private javax.swing.JTextField from;
    private javax.swing.JTextField host;
    private javax.swing.JCheckBox implicitTls;
    private javax.swing.JTable jobTable;
    private javax.swing.JScrollPane jobsScroll;
    private javax.swing.JLabel lblBody;
    private javax.swing.JLabel lblFrom;
    private javax.swing.JLabel lblHost;
    private javax.swing.JLabel lblPassword;
    private javax.swing.JLabel lblPort;
    private javax.swing.JLabel lblRate;
    private javax.swing.JLabel lblRecipients;
    private javax.swing.JLabel lblRetries;
    private javax.swing.JLabel lblSubject;
    private javax.swing.JLabel lblUsername;
    private javax.swing.JPasswordField password;
    private javax.swing.JTextField port;
    private javax.swing.JTextField rate;
    private javax.swing.JTextArea recipients;
    private javax.swing.JScrollPane recipientsScroll;
    private javax.swing.JTextField retries;
    private javax.swing.JCheckBox startTls;
    private javax.swing.JTextField subject;
    private javax.swing.JLabel summary;
    private javax.swing.JTextField username;
    // End of variables declaration//GEN-END:variables
}
