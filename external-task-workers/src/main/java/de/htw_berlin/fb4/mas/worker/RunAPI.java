package de.htw_berlin.fb4.mas.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.htw_berlin.fb4.mas.model.OpeningHours;
import de.htw_berlin.fb4.mas.model.ServicePoint;
import de.htw_berlin.fb4.mas.model.ServicePointResponse;
import jakarta.activation.DataHandler;
import jakarta.mail.*;
import jakarta.mail.Authenticator;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import okhttp3.*;
import org.camunda.bpm.client.task.ExternalTask;
import org.camunda.bpm.client.task.ExternalTaskHandler;
import org.camunda.bpm.client.task.ExternalTaskService;
import org.camunda.bpm.engine.variable.value.FileValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * This class handles external tasks in Camunda, fetches service point locations based on provided address data,
 * prepares an email body with the fetched data, and sends the email.
 */
public class RunAPI implements ExternalTaskHandler {
    private static final Logger log = LoggerFactory.getLogger(RunAPI.class);
    private final Properties mailProperties;

    /**
     * Constructor that loads mail properties from a file.
     */
    public RunAPI() {
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

    /**
     * Executes the external task by fetching service point locations, preparing the email body, and sending the email.
     *
     * @param externalTask the external task
     * @param externalTaskService the external task service
     */
    @Override
    public void execute(ExternalTask externalTask, ExternalTaskService externalTaskService) {
        log.info("Handling external task (Task ID: {} - Process Instance ID {})", externalTask.getId(), externalTask.getProcessInstanceId());

        // Retrieve variables from the external task
        String countryCode = externalTask.getVariable("countryCode");
        String addressLocality = externalTask.getVariable("addressLocality");
        Integer postalCode = externalTask.getVariable("postalCode");
        String streetAddress = externalTask.getVariable("streetAddress");

        String from = externalTask.getVariable("from");
        String to = externalTask.getVariable("to");
        String cc = externalTask.getVariable("cc");
        String subject = externalTask.getVariable("subject");
        String body = externalTask.getVariable("body");

        try {
            // Fetch service point locations
            List<ServicePoint> locations = getLocations(countryCode, addressLocality, postalCode, streetAddress);
            // Prepare the email body
            String generatedBody = prepareMail(locations);
            String finalBody = body + "\n\n" + generatedBody;

            // Send the email
            sendMail(from, to, cc, subject, finalBody, null);

            // Complete the external task
            externalTaskService.complete(externalTask);
        } catch (Exception e) {
            // Handle failure
            externalTaskService.handleFailure(externalTask, "Error while fetching locations", e.getMessage(), 0, 0);
        }
    }

    /**
     * Fetches raw response from the API for service point locations.
     *
     * @param countryCode the country code
     * @param addressLocality the address locality
     * @param postalCode the postal code
     * @param streetAddress the street address
     * @return the response from the API
     * @throws Exception if an error occurs while fetching the data
     */
    private Response getLocationsRaw(String countryCode, String addressLocality, Integer postalCode, String streetAddress) throws Exception {
        OkHttpClient client = new OkHttpClient().newBuilder().build();
        MediaType mediaType = MediaType.parse("text/plain");
        Request request = new Request.Builder()
                .url("https://api.dhl.com/location-finder/v1/find-by-address?countryCode=" + countryCode + "&addressLocality=" + addressLocality + "&postalCode=" + postalCode + "&streetAddress=" + streetAddress + "&serviceType=parcel%3Adrop-off-all&radius=2500&limit=5&hideClosedLocations=false")
                .method("GET", null)
                .addHeader("DHL-API-Key", System.getenv("DHL_API_KEY"))
                .build();
        return client.newCall(request).execute();
    }

    /**
     * Fetches service point locations based on the provided address data.
     *
     * @param countryCode the country code
     * @param addressLocality the address locality
     * @param postalCode the postal code
     * @param streetAddress the street address
     * @return a list of service points
     * @throws Exception if an error occurs while fetching the data
     */
    private List<ServicePoint> getLocations(String countryCode, String addressLocality, Integer postalCode, String streetAddress) throws Exception {
        Response response = getLocationsRaw(countryCode, addressLocality, postalCode, streetAddress);
        String responseBody = response.body().string();
        return new ObjectMapper().readValue(responseBody, ServicePointResponse.class).getLocations();
    }

    /**
     * Prepares the email body with the fetched service point locations.
     *
     * @param locations the list of service points
     * @return the prepared email body
     */
    private String prepareMail(List<ServicePoint> locations) {
        StringBuilder body = new StringBuilder();
        for (ServicePoint location : locations) {
            body.append("Name: ").append(location.getName()).append("\n");
            body.append("Distance: ").append(location.getDistance()).append("m\n");
            body.append("Address: ").append(location.getPlace().getAddress().getStreetAddress()).append(", ")
                    .append(location.getPlace().getAddress().getPostalCode()).append(" ")
                    .append(location.getPlace().getAddress().getAddressLocality()).append(", ")
                    .append(location.getPlace().getAddress().getCountryCode()).append("\n");
            body.append("Opening hours:\n");
            for (OpeningHours hours : location.getOpeningHours()) {
                String dayOfWeek = hours.getDayOfWeek().substring(hours.getDayOfWeek().lastIndexOf('/') + 1);
                body.append("  ").append(dayOfWeek).append(": ")
                        .append(hours.getOpens()).append(" - ").append(hours.getCloses()).append("\n");
            }
            body.append("\n");
        }
        return body.toString();
    }

    /**
     * Sends an email with the provided details.
     *
     * @param from the sender's email address
     * @param to the recipient's email address
     * @param cc the CC email address
     * @param subject the email subject
     * @param body the email body
     * @param attachment the email attachment
     * @throws MessagingException if an error occurs while sending the email
     * @throws IOException if an error occurs while reading the attachment
     */
    private void sendMail(String from, String to, String cc, String subject, String body, FileValue attachment) throws MessagingException, IOException {
        Session session = createSession();

        Message message = new MimeMessage(session);
        message.setFrom(new InternetAddress(from));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        if (cc != null) {
            message.setRecipients(Message.RecipientType.CC, InternetAddress.parse(cc));
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

    /**
     * Creates a mail session with the loaded properties.
     *
     * @return the mail session
     */
    private Session createSession() {
        return Session.getInstance(mailProperties, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(mailProperties.getProperty("mail.smtp.auth.user"), mailProperties.getProperty("mail.smtp.auth.password"));
            }
        });
    }
}