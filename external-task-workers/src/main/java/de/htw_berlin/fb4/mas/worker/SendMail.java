package de.htw_berlin.fb4.mas.worker;

import jakarta.activation.DataHandler;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.camunda.bpm.client.task.ExternalTask;
import org.camunda.bpm.client.task.ExternalTaskHandler;
import org.camunda.bpm.client.task.ExternalTaskService;
import org.camunda.bpm.engine.variable.Variables;
import org.camunda.bpm.engine.variable.value.BytesValue;
import org.camunda.bpm.engine.variable.value.FileValue;
import org.camunda.bpm.engine.variable.value.StringValue;
import org.camunda.bpm.engine.variable.value.TypedValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Properties;

import static jakarta.mail.Message.RecipientType.CC;
import static jakarta.mail.Message.RecipientType.TO;

/**
 * Implementierung eines {@link ExternalTaskHandler} zum Versenden einfacher Text-Mails.
 * <p>
 * Der Handler erwartet, dass die Variablen "from", "to", "subject" und "body" gesetzt sind.
 * <p>
 * Für eine bessere Wiederverwendbarkeit, können diese Variablen über ein
 * <a href="https://docs.camunda.org/manual/7.21/user-guide/process-engine/variables/#input-output-variable-mapping">Input/Output Variable Mapping</a>
 * auf Basis von existierenden Variablen oder im Prozess definierten Werten gesetzt werden. Beispiel:
 * <ul>
 *     <li>from = sales@example.org</li>
 *     <li>to = ${customer_mail_address}</li>
 *     <li>cc = ${sales_mail_address}</li>
 *     <li>subject = Auftragsbestätigung</li>
 *     <li>body = Sehr geehrte Damen und Herren, hiermit bestätigen wir Ihnen Auftrag ${auftragsnummer}.</li>
 *     <li>attachment (optional) = einer dieser Werte:
 *          <ul>
 *              <li>String mit Pfad zur Datei</li>
 *              <li>Objekt vom Typ {@link FileValue}, siehe {@link Variables#fileValue(File)} oder {@link Variables#fileValue(String)}</li>
 *              <li>Objekt vom Typ {@code byte[]}</li>
 *          </ul>
 *     </li>
 *     <li>attachmentName (optional) = wenn attachment ein byte[] ist, dann der String mit dem Dateinamen</li>
 * </ul>
 * <p>
 * Die Konfiguration erfolgt über die Datei mail.properties, die sich im Ausführungsverzeichnis befinden muss.
 * Beim Start aus der IDE ist es das Verzeichnis, das die Datei pom.xml enthält.
 * Die Datei mail.properties muss folgende Einstellungen enthalten:
 * <ul>
 *     <li>mail.smtp.host</li>
 *     <li>mail.smtp.port</li>
 *     <li>mail.smtp.auth.user</li>
 *     <li>mail.smtp.auth.password</li>
 * </ul>
 */
public class SendMail implements ExternalTaskHandler {

    private static final Logger log = LoggerFactory.getLogger(SendMail.class);

    private final Properties mailProperties;

    public SendMail() {
        mailProperties = new Properties();
        try (InputStream inputStream = Files.newInputStream(Path.of("mail.properties").toAbsolutePath())) {
            mailProperties.load(inputStream);
        } catch (Exception e) {
            throw new RuntimeException("Einstellungen zum Versenden von Mails konnten nicht geladen werden", e);
        }

        mailProperties.put("mail.smtp.auth", true);
        mailProperties.put("mail.smtp.starttls.enable", "true");
        mailProperties.put("mail.smtp.ssl.trust", mailProperties.getProperty("mail.smtp.host"));
    }

    @Override
    public void execute(ExternalTask externalTask, ExternalTaskService externalTaskService) {
        log.info("Handling external task (Task ID: {} - Process Instance ID {})", externalTask.getId(), externalTask.getProcessInstanceId());

        String from = externalTask.getVariable("from");
        String to = externalTask.getVariable("to");
        String cc = externalTask.getVariable("cc");
        String subject = externalTask.getVariable("subject");
        String body = externalTask.getVariable("body");
        FileValue attachment = tryGetAttachment(externalTask);

        try {
            log.info("Sending mail with subject '{}' to '{}'{}", subject, to, cc != null ? " (cc: '" + cc + "')" : "");
            sendMail(from, to, cc, subject, body, attachment);

            externalTaskService.complete(externalTask);
        } catch (MessagingException | IOException e) {
            handleFailure(externalTask, externalTaskService, e);
        }
    }

    private FileValue tryGetAttachment(ExternalTask externalTask) {
        TypedValue attachment = externalTask.getVariableTyped("attachment");
        if (attachment instanceof FileValue) {
            return (FileValue) attachment;
        }

        if (attachment instanceof StringValue) {
            Path attachmentPath = null;
            try {
                attachmentPath = Path.of(((StringValue) attachment).getValue());
            } catch (InvalidPathException ignored) {
            }

            if (attachmentPath != null && Files.isRegularFile(attachmentPath)) {
                return Variables.fileValue(attachmentPath.getFileName().toString()).file(attachmentPath.toFile()).create();
            }
        }

        if (attachment instanceof BytesValue) {
            String attachmentName = externalTask.getVariable("attachmentName");
            if (attachmentName != null) {
                return Variables.fileValue(attachmentName).file(((BytesValue) attachment).getValue()).create();
            }
        }

        return null;
    }

    private void sendMail(String from, String to, String cc, String subject, String body, FileValue attachment) throws MessagingException, IOException {
        Session session = createSession();

        Message message = new MimeMessage(session);
        message.setFrom(new InternetAddress(from));
        message.setRecipients(TO, InternetAddress.parse(to));
        if (cc != null) {
            message.setRecipients(CC, InternetAddress.parse(cc));
        }
        message.setSubject(subject);

        if (attachment != null) {
            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(body);

            MimeBodyPart attachmentPart = new MimeBodyPart();
            attachmentPart.setDataHandler(new DataHandler(new ByteArrayDataSource(attachment.getValue().readAllBytes(), "application/octet-stream")));
            attachmentPart.setFileName(attachment.getFilename());

            MimeMultipart content = new MimeMultipart(textPart, attachmentPart);
            message.setContent(content);
        } else {
            message.setText(body);
        }

        Transport.send(message);
    }

    private Session createSession() {
        return Session.getInstance(mailProperties, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(mailProperties.getProperty("mail.smtp.auth.user"), mailProperties.getProperty("mail.smtp.auth.password"));
            }
        });
    }

    private static void handleFailure(ExternalTask externalTask, ExternalTaskService externalTaskService, Exception exception) {
        String errorMessage = "Send Mail Failed";

        StringWriter stackTraceWriter = new StringWriter();
        exception.printStackTrace(new PrintWriter(stackTraceWriter));
        String errorDetails = stackTraceWriter.toString();

        createIncident(externalTask, externalTaskService, errorMessage, errorDetails);
    }

    private static void createIncident(ExternalTask externalTask, ExternalTaskService externalTaskService, String errorMessage, String errorDetails) {
        int retries = 0;
        long retryTimeout = 0;

        log.info("Creating incident for process instance {} with message '{}'", externalTask.getProcessInstanceId(), errorMessage);
        externalTaskService.handleFailure(externalTask, errorMessage, errorDetails, retries, retryTimeout);
    }
}
