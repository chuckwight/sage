package org.chemvantage.sage;

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;

import com.google.cloud.ServiceOptions;
import com.google.gson.JsonParser;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Ignore;
import com.sendgrid.Method;
import com.sendgrid.Request;
import com.sendgrid.Response;
import com.sendgrid.SendGrid;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.Content;
import com.sendgrid.helpers.mail.objects.Email;

@Entity
public class Util {
	@Id Long id = 1L;
	private String HMAC256Secret = "ChangeMe";
	private String reCaptchaSecret = "ChangeMe";
	private String reCaptchaSiteKey = "ChangeMe";
	private int nSageAnswerWasHelpful = 0;
	private int nSageAnswerNotHelpful = 0;
	private String openai_key = "ChangeMe";
	private String salt = "ChangeMe";
	private String announcement = "";
	private String payPalSandboxClientId = "ChangeMe";
	private String payPalSandboxSecret = "ChangeMe";
	private String payPalDefaultClientId = "ChangeMe";
	private String payPalDefaultSecret = "ChangeMe";
	private String sendGridAPIKey = "ChangeMe";
	private static Util u;
	@Ignore static final int price = 5;  // base monthly subscription price set here and checkout_student.js
	@Ignore static String projectId = ServiceOptions.getDefaultProjectId();
	@Ignore static String serverUrl = "https://" + projectId + ".appspot.com";
	
	@Ignore static String head = "<!DOCTYPE html><html lang='en'>\n"
			+ "<head>\n"
			+ "  <meta charset='UTF-8' />\n"
			+ "  <meta name='viewport' content='width=device-width, initial-scale=1.0' />\n"
			+ "  <meta name='description' content='ChemVantage is an LTI app that works with yur LMS for teaching and learning college-level General Chemistry.' />\n"
			+ "  <meta http-equiv='Cache-Control' content='no-cache, no-store, must-revalidate' />\n"
			+ "  <link rel='icon' href='images/logo.png' />\n"
			+ "  <link rel='canonical' href='https://sage.chemvantage.org' />\n"
			+ "  <title>Sage</title>\n"
			+ "  <link href='https://fonts.googleapis.com/css2?family=Poppins:wght@100;200;300;400;500;600;700;800;900&family=Shantell+Sans:wght@300;400;500;600;700;800&display=swap' rel='stylesheet'/>\n"
			+ "  <link rel='stylesheet' href='/css/style.css'>\n"
			+ "</head>\n"
			+ "<body>\n";
	
	@Ignore static String foot = "<footer><p><hr style='width:600px;margin-left:0' />"
			+ "<a style='text-decoration:none;color:#000080;font-weight:bold' href=/index.html>"
			+ "sage</a> | "
			+ "<a href=/terms_and_conditions.html>Terms and Conditions of Use</a> | "
			+ "<a href=/privacy_policy.html>Privacy Policy</a> | "
			+ "<a href=/copyright.html>Copyright</a></footer>\n"
			+ "</body></html>";

	private Util() {}
	
	static public String getAnnouncement() {if (u==null) init(); return u.announcement;}
	static public String getHMAC256Secret() {if (u==null) init(); return u.HMAC256Secret;}
	static public String getOpenAIKey() {if (u==null) init(); return u.openai_key;}
	static public String getReCaptchaSecret() {if (u==null) init(); return u.reCaptchaSecret;}
	static public String getReCaptchaSiteKey() {if (u==null) init(); return u.reCaptchaSiteKey;}
	static public String getSalt() {if (u==null) init(); return u.salt;}
	static public String getSendGridAPIKey() {if (u==null) init(); return u.sendGridAPIKey;}
	static public String getPayPalClientId(String serverName) {
		if (u==null) init();
		if (serverName.contains("localhost")) return u.payPalSandboxClientId;
		else return u.payPalDefaultClientId;
	}
	
	static private void init() {
		if (u==null) {
			try {
				u = ofy().load().type(Util.class).id(1L).safe();
			} catch(Exception e) {
				u = new Util();
				ofy().save().entity(u).now();
			}
		}
	}
	
	static void sageAnswerWasHelpful(boolean response) {
		if (response) u.nSageAnswerWasHelpful++;
		else u.nSageAnswerNotHelpful++;
		ofy().save().entity(u);
	}
	
	static String getPayPalAccessToken(String serverName) throws Exception {
		String access_token = null;
		String access_token_url = null;
		String client_credentials = null;
		if (u==null) init();
		if (serverName.contains("localhost")) { // use PayPal sandbox credentials
			access_token_url = "https://api.sandbox.paypal.com/v1/oauth2/token";
			client_credentials = u.payPalSandboxClientId + ":" + u.payPalSandboxSecret;
		} else { // use PayPal live credentials
			access_token_url = "https://api.paypal.com/v1/oauth2/token";
			client_credentials = u.payPalDefaultClientId + ":" + u.payPalDefaultSecret;
		}
		URL u = new URL(access_token_url + "?grant_type=client_credentials");
		HttpURLConnection uc = (HttpURLConnection) u.openConnection();
		uc.setDoOutput(true);
		uc.setDoInput(true);
		uc.setRequestMethod("POST");
		uc.setRequestProperty("Accept", "application/json");
		uc.setRequestProperty("Accept-Language", "en_US");
		byte[] encoded_client_credentials = Base64.getEncoder().encode(client_credentials.getBytes("UTF-8"));
		uc.setRequestProperty("Authorization", "Basic " + new String(encoded_client_credentials));
		uc.setReadTimeout(15000);  // waits up to 15 s for server to respond
		uc.connect();
		BufferedReader reader = new BufferedReader(new InputStreamReader(uc.getInputStream()));
		access_token = JsonParser.parseReader(reader).getAsJsonObject().get("access_token").getAsString();
		reader.close();
		return access_token;
	}
	
	static int getSageAnswerWasHelpful() {
		return u.nSageAnswerWasHelpful;
	}
	
	static int getSageAnswerNotHelpful() {
		return u.nSageAnswerNotHelpful;
	}
	
	public static void sendEmail(String recipientName, String recipientEmail, String subject, String message) 
			throws Exception {
		Email from = new Email("admin@chemvantage.org","ChemVantage LLC");
		if (recipientName==null) recipientName="";
		Email to = new Email(recipientEmail);
		Content content = new Content("text/html", message);
		Mail mail = new Mail(from, subject, to, content);

		SendGrid sg = new SendGrid(getSendGridAPIKey());
		Request request = new Request();
		request.setMethod(Method.POST);
		request.setEndpoint("mail/send");
		request.setBody(mail.build());
		Response response = sg.api(request);
		if (response.getStatusCode() > 299) throw new Exception("SendGrid Error " + response.getStatusCode() + ": " + response.getBody() + "\nAPIKey: " + Util.getSendGridAPIKey());
	}
}
