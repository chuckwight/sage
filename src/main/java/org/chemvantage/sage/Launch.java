package org.chemvantage.sage;

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Calendar;
import java.util.Date;
import java.util.regex.Pattern;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/launch")
public class Launch extends HttpServlet {

	private static final long serialVersionUID = 1L;
	
	public void doGet(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		
		//  This method permits login using a valid tokenized link.
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html");
		
		try {  // token login
			String token = request.getParameter("Token");
			String hashedId = validateToken(token);
			User user = ofy().load().type(User.class).id(hashedId).now();
			
			Date now = new Date();
			if (user == null) {  					// new user
				out.println(welcomePage(hashedId));
			}
			else if (user.expires.before(now)) {  	// subscription expired
				out.println(checkout(user));
			}
			else {  								// continuing user
				request.getSession().setAttribute("hashedId", hashedId);
				response.sendRedirect("/sage");
			}
		} catch (Exception e) {
			response.sendRedirect("/");;
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
		case "I Agree":  //  new user agrees to terms - from welcomePage()
			hashedId = request.getParameter("HashedId");
			user = new User(hashedId);
			ofy().save().entity(user).now();
			request.getSession().setAttribute("hashedId", hashedId);
			response.sendRedirect("/sage");
			return;
		case "Complete Purchase":  // after PayPal payment - from checkout()
			try {
				if (purchaseComplete(request)) out.println(thankYouPage(request));
				else throw new Exception("Unknown error");
			} catch (Exception e) {
				out.println(e.getMessage()==null?e.toString():e.getMessage());
			}
			return;
		default:
			try {  // login attempt from index.html
				String email = request.getParameter("Email");
				String regexPattern = "^(.+)@(\\S+)$";
				if (!Pattern.compile(regexPattern).matcher(email).matches()) throw new Exception("Not a valid email address");

				hashedId = getHash(email);
				user = ofy().load().type(User.class).id(hashedId).now();
				Date now  = new Date();

				if (user != null && user.expires.after(now) && user.hashedId.equals(request.getSession().getAttribute("hashedId"))) {  // returning user with active session
					out.println(Sage.start(hashedId));
				} else { // no valid session; send login link
					String serverUrl = request.getServerName().contains("localhost")?"http://localhost:8080":Util.serverUrl;
					Util.sendEmail(null,email,"Sage Login Link", tokenMessage(createToken(hashedId),serverUrl));
					out.println(emailSent());
					return;
				}
			} catch (Exception e) {
				response.sendRedirect("/");
			}
		}
	}

	static String checkout(User user) {		
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
				+ "<label><input type=checkbox id=terms onChange=showPurchase();> I understand and agree to the <a href=/terms_and_conditions.html target=_blank>Sage Terms and Conditions of Use</a>.</label> <br/>"
				+ "<label><input type=checkbox id=norefunds onChange=showPurchase();> I understand that all Sage subscription fees are non-refundable.</label> <p>"
				+ "</div>"
				+ "<img src=/images/sage.png alt='Confucius Parrot' style='float:right'>"
				+ "</div><p>"
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
		
		buf.append("<script src='https://www.paypal.com/sdk/js?client-id=" + Util.getPayPalClientId() +"&enable-funding=venmo&currency=USD'></script>\n");
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

	static String createToken(String hashedId) throws Exception {
		Date now = new Date();
		Date exp = new Date(now.getTime() + 360000L);  // 5 minutes from now
		String nonce = Nonce.generateNonce();
		Algorithm algorithm = Algorithm.HMAC256(Util.getHMAC256Secret());
		
		String token = JWT.create()
				.withIssuer(Util.serverUrl)
				.withSubject(hashedId)
				.withExpiresAt(exp)
				.withClaim("nonce", nonce)
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
				+ "The link can only be used once, and it will expire in 5 minutes at " + fiveMinutesFromNow + "."
				+ "</div>"
				+ "<img src=/images/sage.png alt='Confucius Parrot' style='float:right'>"
				+ "</div>"
				+ Util.foot;
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
		return "<h1>Login to Sage</h1>"
			+ "<div style='display:flex; align-items:center;'>"
			+ "<div>"
			+ "Please click the tokenized button below to login to your Sage account.<br/>"
			+ "The link can only be used once, and it will expire in 5 minutes at " + exp + ".<p>"
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
			String nonce = decoded.getClaim("nonce").asString();
			if (!Nonce.isUnique(nonce)) throw new Exception("The login link can only be used once.");
			return decoded.getSubject();
	}
	
	String welcomePage(String hashedId) {
		StringBuffer buf = new StringBuffer(Util.head);
		buf.append("<h1>Welcome to Sage</h1>"
				+ "<div style='max-width:600px;'>"
				+ "Sage is an AI-powered tutor for General Chemistry. "
				+ "You will be guided to mastery of more than 100 different key "
				+ "concepts in the course.<p>"
				+ "We are pleased to offer you a 7-day free trial subscription "
				+ "to Sage. After that time, you may renew your subscription for "
				+ "just $5 USD per month. To accept this offer and start your free "
				+ "trial, please indicate your acceptance of the terms below:<p>"
				+ "</div>"
				+ "<form method=post>"
				+ "<input type=hidden name=HashedId value=" + hashedId + " />"
				+ "<label><input type=checkbox required name=Terms />I agree to the Sage Terms and Conditions of Use</label><br/>"
				+ "<label><input type=checkbox required name=Terms />I understand that Sage subscription fees are nonrefundable.</label><p>"
				+ "<input class=btn type=submit name=UserRequest value='I Agree' />"
				+ "</form>");
		return buf.toString() + Util.foot;
	}
}