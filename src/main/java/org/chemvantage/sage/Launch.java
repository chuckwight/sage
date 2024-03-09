package org.chemvantage.sage;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.regex.Pattern;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/launch")
public class Launch extends HttpServlet {

	private static final long serialVersionUID = 1L;

	public void doGet(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html");
		
		// This method permits login using a valid tokenized link
		
		try {
			String token = request.getParameter("Token");
			String hashedId = validateToken(token);
			Cookie cookie = new Cookie("hashedId",hashedId);
			cookie.setMaxAge(60 * 60 * 24);
			response.addCookie(cookie);
			out.println(Sage.start(hashedId));
		} catch (Exception e) {
			out.println("Error: " + e.getMessage()==null?e.toString():e.getMessage());
		}
	}
	
	public void doPost(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html");
		
		// This method allows login with email and matching Cookie.
		// If the cookie is missing, it returns a tokenized link via email.
		try {
			String email = request.getParameter("Email");
			String regexPattern = "^(.+)@(\\S+)$";
			if (!Pattern.compile(regexPattern).matcher(email).matches()) throw new Exception("Not a valid email address");
			
			String hashedId = getHash(email);
			Cookie[] cookies = request.getCookies();
			if (cookies == null) {  // no recent login
				String serverUrl = request.getServerName().contains("localhost")?"http://localhost:8080":Util.serverUrl;
				Util.sendEmail(null,email,"Sage Login Link", tokenMessage(createToken(hashedId),serverUrl));
				out.println(emailSent());
			} else {  // use Cookie login
				for (Cookie c : cookies) {
					if ("hashedId".equals(c.getName()) && c.getValue().equals(hashedId)) {
						out.println(Sage.start(hashedId));
						return;
					}
				}
			}
		} catch (Exception e) {
			out.println("Error: " + e.getMessage()==null?e.toString():e.getMessage());
		}
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

	static String tokenMessage(String token, String serverUrl) {
		DecodedJWT decoded = JWT.decode(token);
		Date exp = decoded.getExpiresAt();
		return "<h1>Login to Sage</h1>"
				+ "Please use the tokenized link below to login to your Sage account. "
				+ "The link will expire in 5 minutes at " + exp + ".<p>"
				+ "<a href='" + serverUrl + "/launch?Token=" + token + "'>" + serverUrl + "/launch?Token=" + token + "</a>";
	}
	
	static String createToken(String hashedId) throws Exception {
		Date now = new Date();
		Date exp = new Date(now.getTime() + 360000L);  // 5 minutes from now
		String nonce = Nonce.generateNonce();
		Algorithm algorithm = Algorithm.HMAC256(Util.getHMAC256Secret());
		
		String token = JWT.create()
				.withSubject(hashedId)
				.withExpiresAt(exp)
				.withClaim("nonce", nonce)
				.sign(algorithm);
		
		return token;
	}
	
	static String validateToken(String token) throws Exception {
			Algorithm algorithm = Algorithm.HMAC256(Util.getHMAC256Secret());
			JWTVerifier verifier = JWT.require(algorithm).build();
			verifier.verify(token);
			DecodedJWT decoded = JWT.decode(token);
			String nonce = decoded.getClaim("nonce").asString();
			if (!Nonce.isUnique(nonce)) throw new Exception("The login link can onloy be used once.");
			return decoded.getSubject();
	}
	
	static String emailSent() {
		Date now = new Date();
		Date fiveMinutesFromNow = new Date(now.getTime() + 360000L);
		return Util.head 
				+ "<h1>Sage Login Link</h1>"
				+ "We sent an email to your address containing a tokenized link to login to Sage.<p>"
				+ "The link expires in 5 minutes at " + fiveMinutesFromNow + "."
				+ Util.foot;
	}
}