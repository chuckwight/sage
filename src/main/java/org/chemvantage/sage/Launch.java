package org.chemvantage.sage;

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import org.apache.commons.validator.routines.EmailValidator;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;
import com.google.cloud.recaptchaenterprise.v1.RecaptchaEnterpriseServiceClient;
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
	private static final int RETURNING_COOKIE = 1;
	private static final int TOKEN_SENT = 2;
	

	public void doGet(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		
		//  This method permits login using a valid tokenized link.
		String token = request.getParameter("Token");
		if (token==null) response.sendRedirect("/");
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html");
		
		try {  // token login
			String hashedId = validateToken(token);
			User user = ofy().load().type(User.class).id(hashedId).now();
			
			Date now = new Date();
			if (user == null) {  					// new user
				out.println(welcomePage(hashedId));
			}
			else if (user.expires.before(now)) {  	// subscription expired
				out.println(checkout(user, request.getRequestURL().toString()));
			}
			else { // continuing user: set a Cookie with the hashedId value
				Cookie cookie = new Cookie("hashedId", hashedId);
				cookie.setSecure(true);
				cookie.setHttpOnly(true);
				cookie.setMaxAge(60 * 60); // 1 hour
				response.addCookie(cookie);
				
				response.sendRedirect("/sage");
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
		
		switch (userRequest) {
		case "Start":  //  new user agreed to terms - from welcomePage()
			hashedId = request.getParameter("HashedId");
			user = new User(hashedId);
			try {
				user.conceptId = Long.parseLong(request.getParameter("ConceptId"));
			} catch (Exception e) {
				user.conceptId = ofy().load().type(Concept.class).order("orderBy").keys().first().now().getId();
			}
			ofy().save().entity(user).now();
			
			Cookie cookie = new Cookie("hashedId", hashedId);
			cookie.setSecure(true);
			cookie.setHttpOnly(true);
			cookie.setMaxAge(60 * 60); // 1 hour
			response.addCookie(cookie);
			
			response.sendRedirect("/sage");
			return;
		case "Complete Purchase":  // after PayPal payment - from checkout()
			try {
				if (purchaseComplete(request)) {
					out.println(thankYouPage(request));
				} else throw new Exception("Unknown error");
			} catch (Exception e) {
				out.println(e.getMessage()==null?e.toString():e.getMessage());
			}
			return;
		default:
			try {  // login attempt from index.html
				String gRecaptchaToken = request.getParameter("g-recaptcha-response");
				if (gRecaptchaToken == null) {
					throw new Exception("The reCaptcha token was missing. Your browser may have cached "
							+ "an older version of the <a href=/ >home page</a>. Please clear the "
							+ "browser's cached pages and try again.");
				}
				
				int captchaScore = Math.round(createAssessment(gRecaptchaToken,"request_login_token"));
				
				String email = request.getParameter("Email").trim().toLowerCase();
				if (!EmailValidator.getInstance().isValid(email)) {
					launchCounters[INVALID_EMAIL][captchaScore]++;
					throw new Exception("Not a valid email address");
				}

				hashedId = getHash(email);
				user = ofy().load().type(User.class).id(hashedId).now();
				Date now  = new Date();

				if (user != null && user.expires.after(now) && user.hashedId.equals(Sage.getFromCookie(request, response))) {  // returning user with active Cookie
					launchCounters[RETURNING_COOKIE][captchaScore]++;
					out.println(Sage.start(user));
				} else { // no valid Cookie; send login link
					Util.sendEmail(null,email,"Sage Login Link", tokenMessage(createToken(hashedId),request.getRequestURL().toString()));
					launchCounters[TOKEN_SENT][captchaScore]++;
					out.println(emailSent());
					return;
				}
			} catch (Exception e) {
				out.println(e.getMessage()==null?e.toString():e.getMessage());
			}
		}
	}

	static String checkout(User user, String serverURL) {		
		/*
		 * The base monthly price (currently $5.00) is set in the checkout_student.js file
		 */
		StringBuffer buf = new StringBuffer(Util.head);
		buf.append("<div style='width:600px; display:flex; align-items:center;'>"
				+ "<div>"
				+ "<h1>Your subscription to Sage has expired</h1>"
				+ "Expiration: " + user.expires + "<p>"
				+ "To continue the journey through more than 100 key concepts in General Chemistry, please "
				+ "indicate your agreement with the two statements below by checking the boxes.<p>"
				+ "</div>"
				+ "<img src=/images/sage.png alt='Confucius Parrot' style='float:right'>"
				+ "</div><p>"
				+ "<label><input type=checkbox id=terms onChange=showPurchase();> I understand and agree to the <a href=/terms_and_conditions.html target=_blank>Sage Terms and Conditions of Use</a>.</label> <br/>"
				+ "<label><input type=checkbox id=norefunds onChange=showPurchase();> I understand that all Sage subscription fees are non-refundable.</label> <p>"
				+ "<div id=purchase style='display:none'>\n");
				
		buf.append("Select the number of months you wish to purchase: "
				+ "<select id=nMonthsChoice onChange=updateAmount();>"
				+ "<option value=1>1 month</option>"
				+ "<option value=2>2 months</option>"
				+ "<option value=5 selected>5 months</option>"
				+ "<option value=12>12 months</option>"
				+ "</select><p>"
				+ "Select your preferred payment method below. When the transaction is completed, your subscription will be activated immediately."
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
		buf.append("<form id=activationForm method=post>"
				+ "<input type=hidden name=UserRequest value='Complete Purchase' />"
				+ "<input type=hidden name=NMonths id=nmonths />"
				+ "<input type=hidden name=AmountPaid id=amtPaid />"
				+ "<input type=hidden name=OrderDetails id=orderdetails />"
				+ "<input type=hidden name=HashedId value='" + user.hashedId + "' />"
				+ "</form>");
	
		return buf.toString() + Util.foot;
	}

	static int createAssessment(String token, String recaptchaAction) throws Exception {
		// create an Assessment of the Google reCaptcha token 
		// (see https://cloud.google.com/recaptcha-enterprise/docs/create-assessment-website#create-assessment-Java)
		
		RecaptchaEnterpriseServiceClient client = RecaptchaEnterpriseServiceClient.create();
		Event event = Event.newBuilder().setSiteKey(Util.getReCaptchaSiteKeyt()).setToken(token).build();
		CreateAssessmentRequest createAssessmentRequest =
			CreateAssessmentRequest.newBuilder()
				.setParent(ProjectName.of(Util.projectId).toString())
				.setAssessment(Assessment.newBuilder().setEvent(event).build())
				.build();
		Assessment response = client.createAssessment(createAssessmentRequest);
		if (!response.getTokenProperties().getValid()) {
	        throw new Exception("The CreateAssessment call failed because the token was: "
	                + response.getTokenProperties().getInvalidReason().name());
		}
		if (!response.getTokenProperties().getAction().equals(recaptchaAction)) {
			throw new Exception("The action attribute in the reCaptcha response token "
					+ "does not match the expected action ('request_login_token')");
		}
	    
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
	}
	
	static String createToken(String hashedId) throws Exception {
		Date now = new Date();
		Date exp = new Date(now.getTime() + 360000L);  // 5 minutes from now
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
		Date fiveMinutesFromNow = new Date(now.getTime() + 360000L);
		return Util.head 
				+ "<h1>Check Your Email</h1>"
				+ "<div style='width:600px; display:flex; align-items:center;'>"
				+ "<div>"
				+ "We sent an email to your address containing a tokenized link to login to Sage.<p>"
				+ "The link expires in 5 minutes at <br/>" + fiveMinutesFromNow + "."
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
		buf.append("<tr><td>Returning Cookie</td><td>" + launchCounters[1][0] + "</td><td>" + launchCounters[1][1] + "</td><td>" + launchCounters[1][2] + "</td><td>" + launchCounters[1][3] + "</td><td>" + launchCounters[1][4] + "</td><td>" + launchCounters[1][5] + "</td><td>" + launchCounters[1][6] + "</td><td>" + launchCounters[1][7] + "</td><td>" + launchCounters[1][8] + "</td><td>" + launchCounters[1][9] + "</td><td>" + launchCounters[1][10] + "</td></tr>");
		buf.append("<tr><td>Token Sent</td><td>" + launchCounters[2][0] + "</td><td>" + launchCounters[2][1] + "</td><td>" + launchCounters[2][2] + "</td><td>" + launchCounters[2][3] + "</td><td>" + launchCounters[2][4] + "</td><td>" + launchCounters[2][5] + "</td><td>" + launchCounters[2][6] + "</td><td>" + launchCounters[2][7] + "</td><td>" + launchCounters[2][8] + "</td><td>" + launchCounters[2][9] + "</td><td>" + launchCounters[2][10] + "</td></tr>");
		buf.append("</table>");
		return buf.toString();
	}

	static boolean purchaseComplete(HttpServletRequest request) throws Exception {
			int nmonths = Integer.parseInt(request.getParameter("NMonths"));
			String hashedId = request.getParameter("HashedId");
			if (!request.getParameter("OrderDetails").contains(hashedId)) throw new Exception("Not a valid user.");
			
			// At this point it looks like a valid purchase; update the User's expiration date
			Calendar cal = Calendar.getInstance();
			cal.add(Calendar.MONTH, nmonths);
			
			User user = ofy().load().type(User.class).id(hashedId).safe();
			user.expires = cal.getTime();
			ofy().save().entity(user).now();
			request.getSession().setAttribute("hashedId", hashedId);
			
			return true;
	}

	static String thankYouPage(HttpServletRequest request) {
		StringBuffer buf = new StringBuffer(Util.head);
		String hashedId = request.getParameter("HashedId");
		String orderDetails = request.getParameter("OrderDetails");
		
		User user = ofy().load().type(User.class).id(hashedId).now();
		
		buf.append("<h1>Thank you for your purchase</h1>"
				+ "It is now " + new Date() + "<p>"
				+ "Your Sage subscription expires " + user.expires + "<p>"
				+ "Please keep a copy of this page as proof of purchase.<p>"
				+ "<a class=btn role=button href='/sage'>Continue</a><p>"
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
			+ "Please click the tokenized button below to login to your Sage account.<br/>"
			+ "The link expires in 5 minutes at " + exp + ".<p>"
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
			//String nonce = decoded.getClaim("nonce").asString();
			//if (!Nonce.isUnique(nonce)) throw new Exception("The login link can only be used once.");
			return decoded.getSubject();
	}
	
	String welcomePage(String hashedId) {
		StringBuffer buf = new StringBuffer(Util.head);
		List<Concept> firstConcepts = ofy().load().type(Concept.class).order("orderBy").limit(4).list();
		buf.append("<h1>Welcome to Sage</h1>"
				+ "<h2>Sage is an AI-powered tutor for General Chemistry.</h2>"
				+ "<div style='max-width:800px;'>"
				+ "<img src=/images/sage.png alt='Confucius Parrot' style='float:right'>"
				+ "You will be guided through a series of questions and problems, with the Sage at your side, "
				+ "ready to provide help whenever you need it. This tutorial is organized around "
				+ "100 key concepts that are normally taught in a college-level General Chemistry course.<p>"
				+ "Accept the terms below to begin your free trial. After 7 days, you can extend your "
				+ "subscription for only $5 USD per month.<p>"
				+ "</div>"
				+ "<form method=post>"
				+ "<input type=hidden name=HashedId value=" + hashedId + " />"
				+ "<label><input type=checkbox required name=Terms />I agree to the <a href=/terms_and_conditions.html target=_blank>Sage Terms and Conditions of Use</a></label><br/>"
				+ "<label><input type=checkbox required name=Terms />I understand that Sage subscription fees are nonrefundable.</label><p>"
				+ "Select the starting point that best fits your needs:<br/>"
				+ "<label><input type=radio name=ConceptId value='" + firstConcepts.get(0).id + "' checked > I'm getting ready to take a General Chemistry class.</label><br/>"
				+ "<label><input type=radio name=ConceptId value='" + firstConcepts.get(3).id + "' > I'm ready! Start the General Chemistry tutorial.</label><br/>"
				+ "<input type=hidden name=UserRequest value=Start />"
				+ "<input class=btn type=submit value='Start My 7-day Free Trial Now' />"
				+ "</form>");
		return buf.toString() + Util.foot;
	}
}