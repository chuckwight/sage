package org.chemvantage.sage;

import java.io.IOException;
import java.io.PrintWriter;

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
		/*
		 *  To access this servlet, the request MUST contain either an email
		 *  address or a token.
		 *  1) Email: This method compares the hashed email to the Cookie "hashedId". 
		 *     If matched, transfers control to Sage servlet.
		 *     Otherwise, sends a tokenized login link to the email address.
		 *  2) Token: This method validates the token, sets a Cookie and transfers to Sage.
		 *  
		 *  Failed: send error message and provide link to index.html
		 */
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html");
		
		try {
			String token = request.getParameter("Token");
			String email = request.getParameter("Email");
			if (token != null) {
				String hashedId = validateToken(token);
				Cookie c = new Cookie("hashedId",hashedId);
				response.addCookie(c);
				out.println(Sage.start(hashedId));
			} else if (email != null) {
				String hashedId = Util.getHash(email);
				Cookie[] cookies = request.getCookies();
				for (Cookie c : cookies) {
					if ("hashedId".equals(c.getName()) && c.getValue().equals(hashedId)) {
						out.println(Sage.start(hashedId));
						return;
					}
				}
				Util.sendEmail(email,tokenMessage);
				out.println(emailSent());
			}
		} catch (Exception e) {
			
		}
	}
	
	private String validateToken(String token) throws Exception {
		
		return hashedId;
	}
}