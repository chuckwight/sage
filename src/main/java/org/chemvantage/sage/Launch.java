package org.chemvantage.sage;

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Random;
import java.util.TimeZone;

import org.apache.commons.validator.routines.EmailValidator;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;
import com.google.cloud.recaptchaenterprise.v1.RecaptchaEnterpriseServiceClient;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.recaptchaenterprise.v1.Assessment;
import com.google.recaptchaenterprise.v1.CreateAssessmentRequest;
import com.google.recaptchaenterprise.v1.Event;
import com.google.recaptchaenterprise.v1.ProjectName;
//import com.google.recaptchaenterprise.v1.RiskAnalysis.ClassificationReason;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/launch")
public class Launch extends HttpServlet {

	private static final long serialVersionUID = 1L;
	
	/* The 2D array launchCounters stores launch statistics.
	 * There are 3 possible initial outcomes for any launch:
	 * 0: invalidEmail - address submitted failed validation test
	 * 1: returningCookie - user already has an active cookie
	 * 2: tokenSent - a token was sent but never resubmitted
	 *
	 * Each array has 11 elements, corresponding to Captcha scores 0-10
	 * that hold the current count of completed launches with these scores.
	 * Scores are integer values translated 10X from Google reCaptcha scores 0.0 - 1.0
	 */
	private static int[][] launchCounters = new int[3][11];
	private static final int INVALID_EMAIL = 0;
	private static final int COOKIE_LOGIN = 1;
	private static final int TOKEN_SENT = 2;
	

	public void doGet(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html");
		
		// AJAX subscription verification
		String hashedId = request.getParameter("verify");
		if (hashedId != null) {
			User user = ofy().load().type(User.class).id(hashedId).now();
			if (user != null && user.expired()) out.println("true");
			else response.setStatus(204);
			return;
		}
		
		//  This method permits login using a valid tokenized link.
		String token = request.getParameter("Token");
		if (token==null) response.sendRedirect("/");
		
		try {  // token login
			hashedId = validateToken(token);
			User user = ofy().load().type(User.class).id(hashedId).now();

			if (user == null) {  	// new user
				out.println(welcomePage(hashedId));
			}
			else if (user.expired()) {  	// no tokens remaining
				out.println(checkout(user, request.getRequestURL().toString()));
			}
			else { // continuing user: set a Cookie with the hashedId value
				Cookie cookie = new Cookie("hashedId", hashedId);
				cookie.setSecure(true);
				cookie.setHttpOnly(true);
				cookie.setMaxAge(60 * 60); // 1 hour
				response.addCookie(cookie);
				out.println(welcomeBackPage(user));
				//out.println(Sage.menuPage(user, Sage.getScore(user)));
			}
		} catch (Exception e) {
			out.println(errorPage(e));
		}
	}
	
	public void doPost(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html");
		
		String userRequest = request.getParameter("UserRequest");
		if (userRequest == null) userRequest = "";
		
		String hashedId = null;
		User user = null;
		
		StringBuffer debug = new StringBuffer("Debug: ");
		try {
			switch (userRequest) {
			case "Start":  //  new user agreed to terms - from welcomePage()
				hashedId = validateToken(request.getParameter("token"));
				Cookie cookie = new Cookie("hashedId", hashedId);
				cookie.setSecure(true);
				cookie.setHttpOnly(true);
				cookie.setMaxAge(60 * 60); // 1 hour
				response.addCookie(cookie);
				user = new User(hashedId);
				ofy().save().entity(user).now();
				response.sendRedirect("/sage?UserRequest=menu");
				return;
			case "Complete Purchase":  // after PayPal payment - from checkout()
				if (purchaseComplete(request)) {
					hashedId = request.getParameter("HashedId");
					cookie = new Cookie("hashedId", hashedId);
					cookie.setSecure(true);
					cookie.setHttpOnly(true);
					cookie.setMaxAge(60 * 60); // 1 hour
					response.addCookie(cookie);
					
					out.println(thankYouPage(request));
				} else out.println("Sorry, something went wrong.");
				return;
			default:
				// login attempt from index.html
				debug.append("3");
				String gRecaptchaToken = request.getParameter("g-recaptcha-response");
				if (gRecaptchaToken == null) {
					throw new Exception("The reCaptcha token was missing. Your browser may have cached "
							+ "an older version of the <a href=/?v=" + new Random().nextInt() + " >home page</a>. Please clear your "
							+ "browser's cached pages and try again.");
				}
				debug.append("a");
				
				int captchaScore = 0; //Math.round(createAssessment(gRecaptchaToken,"request_login_token"));
				debug.append("b");
				
				String email = request.getParameter("Email").trim().toLowerCase();
				if (!EmailValidator.getInstance().isValid(email)) {
					launchCounters[INVALID_EMAIL][captchaScore]++;
					throw new Exception("We were unable to validate the email address: " + email);
				}
				debug.append("c");
				
				hashedId = getHash(email);
				user = ofy().load().type(User.class).id(hashedId).now();
				User cookieUser = Sage.getFromCookie(request, response); // may be null
				if (user != null && !user.expired() && cookieUser != null && user.hashedId.equals(cookieUser.hashedId)) {  // returning user with active Cookie
					debug.append("i");
					launchCounters[COOKIE_LOGIN][captchaScore]++;
					out.println(Sage.menuPage(user, Sage.getScore(user)));
					//out.println(Sage.start(user,Sage.getScore(user)));
				} else { // no valid Cookie; send login link
					debug.append("ii");
					Util.sendEmail(null,email,"Sage Login Link", tokenMessage(createToken(hashedId),request.getRequestURL().toString()));
					launchCounters[TOKEN_SENT][captchaScore]++;
					out.println(emailSent());
					return;
				}
			}
		} catch (Exception e) {
			out.println(Util.head 
					+ "<h1>Uh oh... we encountered an error.</h1>"
					+ (e.getMessage()==null?e.toString():e.getMessage()) + "<br/>"
					+ debug.toString() + "<p>"
					+ Util.foot);
		}
	}

	static String checkout(User user, String serverURL) {		
		/*
		 * The base monthly price (currently $5.00) is set in the checkout_student.js file
		 */
		StringBuffer buf = new StringBuffer(Util.head);
		buf.append("<h1>You have no more Sage tokens remaining</h1>"
				+ "<div style='width:600px; display:flex; align-items:center;'>"
				+ "<div>"
				+ "When your account is active, you can earn free tokens. See details <a href=/pricing.html>here</a>.<p>"
				+ "To continue your journey through more than 100 key concepts in General Chemistry, please "
				+ "indicate your agreement with the two statements below by checking the boxes.<p>"
				+ "</div>"
				+ "<img src=/images/sage.png alt='Confucius Parrot' style='float:right'>"
				+ "</div><p>"
				+ "<label><input type=checkbox id=terms onChange=showPurchase();> I understand and agree to the Sage <a href=/terms_and_conditions.html target=_blank>Sage Terms and Conditions of Use</a>.</label> <br/>"
				+ "<label><input type=checkbox id=norefunds onChange=showPurchase();> I understand that all Sage purchases are non-refundable.</label> <p>");
				
		buf.append("<div id=purchase style='display:none'>"
				+ "Select the number of tokens you wish to purchase: "
				+ "<select id=nTokens onChange=updateAmount();>"
				+ "<option value=100 selected>100 tokens</option>"
				+ "<option value=500>500 tokens</option>"
				+ "</select><p>"
				+ "Select your preferred payment method below. When the transaction is completed, you can restart the tutorial immediately."
				+ "<h2>Purchase: <span id=amt></span></h2>"
				+ "  <div id=\"smart-button-container\">"
				+ "    <div style=\"text-align: center;\">"
				+ "      <div id=\"paypal-button-container\"></div>"
				+ "    </div>"
				+ "  </div>"
				+ "</div>\n");
		
		buf.append("<script src='https://www.paypal.com/sdk/js?client-id=" + Util.getPayPalClientId(serverURL) +"&enable-funding=venmo&currency=USD'></script>\n");
		buf.append("<script src='/js/checkout_student.js'></script>");
		buf.append("<script>initPayPalButton('" + user.hashedId + "')</script>");
		
		// Add a hidden activation form to submit via javascript when the payment is successful
		buf.append("<form id=activationForm method=post action='/launch' >"
				+ "<input type=hidden name=UserRequest value='Complete Purchase' />"
				+ "<input type=hidden name=OrderDetails id=orderdetails />"
				+ "<input type=hidden name=HashedId value='" + user.hashedId + "' />"
				+ "</form>");
		
		// Javascript verifies that subscription has expired to prevent duplicate payments
		buf.append("<script>verifySubscription('" + user.hashedId + "');</script>");
		
		return buf.toString() + Util.foot;
	}

	static int createAssessment(String token, String recaptchaAction) throws Exception {
		// create an Assessment of the Google reCaptcha token 
		// (see https://cloud.google.com/recaptcha-enterprise/docs/create-assessment-website#create-assessment-Java)
		StringBuffer debug = new StringBuffer("createAssessment:");
		try {
			debug.append("1");
			RecaptchaEnterpriseServiceClient client = RecaptchaEnterpriseServiceClient.create();
			debug.append("2<br/>" 
					+ "RecaptchaSiteKey: " + Util.getReCaptchaSiteKey() + "<br/>" 
					+ "RecaptchaToken: " + token);
			Event event = Event.newBuilder().setSiteKey(Util.getReCaptchaSiteKey()).setToken(token).build();
			debug.append("3");
			CreateAssessmentRequest createAssessmentRequest =
					CreateAssessmentRequest.newBuilder()
					.setParent(ProjectName.of(Util.projectId).toString())
					.setAssessment(Assessment.newBuilder().setEvent(event).build())
					.build();
			debug.append("4");
			Assessment response = client.createAssessment(createAssessmentRequest);
			debug.append("5");
			if (!response.getTokenProperties().getValid()) {
				throw new Exception("The CreateAssessment call failed because the token was: "
						+ response.getTokenProperties().getInvalidReason().name());
			}
			if (!response.getTokenProperties().getAction().equals(recaptchaAction)) {
				throw new Exception("The action attribute in the reCaptcha response token "
						+ "does not match the expected action ('request_login_token')");
			}
			debug.append("4");
			
			// This section is reserved for future use if the reasons are desired
			/*
			StringBuffer reasons = new StringBuffer();
			for (ClassificationReason reason : response.getRiskAnalysis().getReasonsList()) {
		        reasons.append(reason + "<br/>");
			}
			*/
			
			// Google reCaptcha scores are floats ranging from 0.0 to 1.0
			// These are multiplied 10X and rounded to integer values from 0 to 10
			return Math.round(10*response.getRiskAnalysis().getScore());
			
		} catch (Exception e) {
			throw new Exception((e.getMessage()==null?e.toString():e.getMessage()) + debug.toString());
		}
	}
	
	static String createToken(String hashedId) throws Exception {
		Date now = new Date();
		Date exp = new Date(now.getTime() + 600000L);  // 5 minutes from now
		Algorithm algorithm = Algorithm.HMAC256(Util.getHMAC256Secret());
		
		String token = JWT.create()
				.withIssuer(Util.serverUrl)
				.withSubject(hashedId)
				.withExpiresAt(exp)
				.sign(algorithm);
		
		return token;
	}

	static String emailSent() {
		Date now = new Date();
		Date tenMinutesFromNow = new Date(now.getTime() + 600000L);
		return Util.head 
				+ "<h1>Check Your Email</h1>"
				+ "<div style='width:600px; display:flex; align-items:center;'>"
				+ "<div>"
				+ "We sent an email to your address containing a secure link to login to Sage.<p>"
				+ "The link expires in 10 minutes at <br/>" +tenMinutesFromNow + "."
				+ "</div>"
				+ "<img src=/images/sage.png alt='Confucius Parrot' style='float:right'>"
				+ "</div>"
				+ Util.foot;
	}
	
	static String errorPage(Exception e) {
		StringBuffer buf = new StringBuffer(Util.head);
		buf.append("<h1>Sorry! An unexpected error occurred.</h1>"
				+ (e.getMessage()==null?e.toString():e.getMessage()) + "<p>"
				+ "<a href=/ >Return to the login page</a>");
		return buf.toString() + Util.foot;
	}

	static String getHash(String email) throws Exception {
		MessageDigest md = MessageDigest.getInstance("SHA-256");
		byte[] bytes = md.digest((email + Util.getSalt()).getBytes(StandardCharsets.UTF_8));
		StringBuilder sb = new StringBuilder();
		for (byte b : bytes) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}
	
	static String getLaunchStats() {
		StringBuffer buf = new StringBuffer();
		buf.append("<table>");
		// header row:
		buf.append("<tr><th>Outcome|Score</th><th>0.0</th><th>0.1</th><th>0.2</th><th>0.3</th><th>0.4</th><th>0.5</th><th>0.6</th><th>0.7</th><th>0.8</th><th>0.9</th><th>1.0</th></tr>");
		// data from counter arrays
		buf.append("<tr><td>Invalid Email</td><td>" + launchCounters[0][0] + "</td><td>" + launchCounters[0][1] + "</td><td>" + launchCounters[0][2] + "</td><td>" + launchCounters[0][3] + "</td><td>" + launchCounters[0][4] + "</td><td>" + launchCounters[0][5] + "</td><td>" + launchCounters[0][6] + "</td><td>" + launchCounters[0][7] + "</td><td>" + launchCounters[0][8] + "</td><td>" + launchCounters[0][9] + "</td><td>" + launchCounters[0][10]+ "</td></tr>");
		buf.append("<tr><td>Cookie Login</td><td>" + launchCounters[1][0] + "</td><td>" + launchCounters[1][1] + "</td><td>" + launchCounters[1][2] + "</td><td>" + launchCounters[1][3] + "</td><td>" + launchCounters[1][4] + "</td><td>" + launchCounters[1][5] + "</td><td>" + launchCounters[1][6] + "</td><td>" + launchCounters[1][7] + "</td><td>" + launchCounters[1][8] + "</td><td>" + launchCounters[1][9] + "</td><td>" + launchCounters[1][10] + "</td></tr>");
		buf.append("<tr><td>Token Sent</td><td>" + launchCounters[2][0] + "</td><td>" + launchCounters[2][1] + "</td><td>" + launchCounters[2][2] + "</td><td>" + launchCounters[2][3] + "</td><td>" + launchCounters[2][4] + "</td><td>" + launchCounters[2][5] + "</td><td>" + launchCounters[2][6] + "</td><td>" + launchCounters[2][7] + "</td><td>" + launchCounters[2][8] + "</td><td>" + launchCounters[2][9] + "</td><td>" + launchCounters[2][10] + "</td></tr>");
		buf.append("</table>");
		return buf.toString();
	}

	static boolean purchaseComplete(HttpServletRequest request) throws Exception {
		JsonObject orderDetails = null;
		String access_token = null;
		try {
			// get the PayPal transactionId from the user's browser
			String transactionId = JsonParser.parseString(request.getParameter("OrderDetails")).getAsJsonObject().get("id").getAsString();
			
			// contact PayPal directly to get the order details
			URL u = new URL("https://api." + (request.getServerName().contains("localhost")?"sandbox.":"") + "paypal.com/v2/checkout/orders/" + transactionId);
			HttpURLConnection uc = (HttpURLConnection) u.openConnection();
			uc.setRequestMethod("GET");
			access_token = Util.getPayPalAccessToken(request.getServerName());
			uc.setRequestProperty("Authorization", "Bearer " + access_token);
			uc.setReadTimeout(15000);  // waits up to 15 s for server to respond
			uc.connect();
			BufferedReader reader = new BufferedReader(new InputStreamReader(uc.getInputStream()));
			orderDetails = JsonParser.parseReader(reader).getAsJsonObject();
			reader.close();
			
			// check the status, current Date/Time and amount paid in USD
			if (!orderDetails.get("status").getAsString().equals("COMPLETED")) throw new Exception("Order status not COMPLETED.");
			SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
			dateFormat.setLenient(true);
			dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
			Date update_time = dateFormat.parse(orderDetails.get("update_time").getAsString());
			long seconds = (new Date().getTime()-update_time.getTime())/1000L;
			if (seconds < -120 || seconds > 6000) throw new Exception("Purchase time is invalid: " + update_time);
			JsonObject amount = orderDetails.get("purchase_units").getAsJsonArray().get(0).getAsJsonObject().get("amount").getAsJsonObject();
			if (!amount.get("currency_code").getAsString().equals("USD")) throw new Exception("Payment was not in USD.");
			int value = (int)amount.get("value").getAsDouble();
				
			// calculate the number of tokens purchased from the amount paid
			int nTokens = 0;
			switch (value) {
			case Util.price: nTokens=100; break;
			case 4*Util.price: nTokens=500; break;
			default: throw new Exception("Amount paid was not valid.");
			}
			
			// Add purchased tokens to the user's account
			String hashedId = request.getParameter("HashedId");
			User user = ofy().load().type(User.class).id(hashedId).now();
			if (user==null) throw new Exception("User was not found in the datastore");
			user.addTokens(nTokens);
			return true;
		} catch (Exception e) {  // FAILED PayPal payment transaction!
			String message = "<h1>Failed PayPal Payment</h1>"
					+ "Error: " + e.toString() + ": " + e.getMessage() + "<p>"
					+ "Please contact admin@chemvantage.org if you need assistance.<p>"
					+ "User-provided order details:<br/>" + request.getParameter("OrderDetails") + "<p>"
					+ "Access token: " + access_token + "<p>"
					+ "PayPal-provided details:<br/>" + (orderDetails==null?"(null)":orderDetails.toString());
			Util.sendEmail("Sage Admin", "admin@chemvantage.org", "Failed PayPal Payment", message);
			throw new Exception(message);
		}
	}

	static String thankYouPage(HttpServletRequest request) {
		StringBuffer buf = new StringBuffer(Util.head);
		String orderDetails = request.getParameter("OrderDetails");
		User user = ofy().load().type(User.class).id(request.getParameter("HashedId")).now();
		buf.append("<h1>Thank you for your purchase</h1>"
				+ "<div style='width:600px; display:flex; align-items:center;'>"
				+ "<div>"
				+ "Date: " + new Date() + "<p>"
				+ "Your Sage account now has " + user.tokensRemaining() + " tokens remaining. "
				+ "See the <a href='/pricing.html'>pricing</a> page for details of how tokens work.<p>"
				+ "Please keep a copy of this page as proof of purchase.<p>"
				+ "<a class=btn role=button href='/sage?UserRequest=menu'>Continue</a><p>"
				+ "</div>"
				+ "<img src=/images/sage.png alt='Confucius Parrot' style='float:right'>"
				+ "</div><p>"
				+ "Purchase Details:<br/>" + orderDetails + "<p>");
		return buf.toString() + Util.foot;
	}

	static String tokenMessage(String token, String serverUrl) {
		DecodedJWT decoded = JWT.decode(token);
		Date exp = decoded.getExpiresAt();
		serverUrl = serverUrl.substring(0,serverUrl.lastIndexOf("/launch"));  // URL of naked domain
		return "<h1>Login to Sage</h1>"
			+ "<div style='display:flex; align-items:center;'>"
			+ "<div>"
			+ "Please click the button below to login to your Sage account.<br/>"
			+ "The secure link expires in 10 minutes at " + exp + ".<p>"
			+ "<a href='" + serverUrl + "/launch?Token=" + token + "'>"
			+ "<button style='border:none;color:white;padding:10px 10px;margin:4px 2px;font-size:16px;cursor:pointer;border-radius:10px;background-color:blue;'>"
			+ "Login to Sage</button></a>"
			+ "</div>"
			+ "<img src='" + decoded.getIssuer() + "/images/sage.png' alt='Confucius Parrot' style='float:right'>\n"
			+ "</div>";
	}

	static String validateToken(String token) throws Exception {
			Algorithm algorithm = Algorithm.HMAC256(Util.getHMAC256Secret());
			JWTVerifier verifier = JWT.require(algorithm).build();
			verifier.verify(token);
			DecodedJWT decoded = JWT.decode(token);
			return decoded.getSubject();
	}
	
	static String welcomeBackPage(User user) {
		StringBuffer buf = new StringBuffer(Util.head);
		buf.append("<h1>Welcome Back to Sage</h1>"
				+ "<div style='max-width:800px;'>"
				+ "<img src=/images/sage.png alt='Confucius Parrot' style='float:right'>"
				+ "Your account has " + user.tokensRemaining() + " tokens remaining.<br/>"
				+ "It will expire at " + user.expiresAt() + ".<p>"
				+ "To earn more tokens and keep your account free, click on any numbered chapter below "
				+ "to view the associated key concepts. Then click on a key concept to start the tutorial.<br/>"
				+ "You may start anywhere, but Sage has indicated a good starting point based on your current scores.<p>"
				+ "</div>");
		return buf.toString() + Sage.conceptsMenu(user, Sage.getScore(user));
}
	
	static String welcomePage(String hashedId) throws Exception {
		StringBuffer buf = new StringBuffer(Util.head);
		buf.append("<h1>Welcome to Sage</h1>"
				+ "<h2>Sage is an intelligent tutor for General Chemistry.</h2>"
				+ "<div style='max-width:800px;'>"
				+ "<img src=/images/sage.png alt='Confucius Parrot' style='float:right'>"
				+ "You will be guided through a series of questions and problems, with the Sage at your side, "
				+ "ready to provide help whenever you need it. This tutorial is organized by "
				+ "146 key concepts that are normally taught in a college-level General Chemistry course."
				+ "<h2>Your account starts with 100 free tokens</h2>"
				+ "You need at least 1 token to start a session. Tokens expire at the rate of 1 token per hour, "
				+ "so 100 tokens will last a little more than 4 days."
				+ "<h2>So Keep Working To Keep Your Account Free</h2>"
				+ "Each time you complete a key concept, Sage awards you 100 additional free tokens "
				+ "(20 tokens for each of 5 levels). Thus you have the potential to earn up to 14,600 tokens "
				+ "(20 months of Sage tutorials) absolutely free, with no credit card required.<p>"
				+ "However, if you run out of tokens you may purchase 100 tokens for $5.00 USD. "
				+ "Let's hope you won't need to do that."
				+ "<h2>Activate your Sage account</h2>"
				+ "Check the boxes below to indicate your agreement:<p>"
				+ "</div>"
				+ "<form method=post>"
				+ "<input type=hidden name=token value=" + createToken(hashedId) + " />"
				+ "<label><input type=checkbox required name=Terms />I agree to the <a href=/terms_and_conditions.html target=_blank>Sage Terms and Conditions of Use</a></label><br/>"
				+ "<label><input type=checkbox required name=Terms />I understand that Sage purchases are nonrefundable.</label><p>"
				+ "<input type=hidden name=UserRequest value=Start />"
				+ "<input class=btn type=submit value='Activate My Free Sage Account' />"
				+ "</form>");
		return buf.toString() + Util.foot;
	}
}