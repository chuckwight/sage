package org.chemvantage.sage;

import static com.googlecode.objectify.ObjectifyService.ofy;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/chapters")
public class ChapterManager extends HttpServlet {

	private static final long serialVersionUID = 1L;
	
	public void doGet(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
		
		PrintWriter out = response.getWriter();
		response.setContentType("text/html");
		
		if (!Util.projectId.equals("sage-416602")) {
			out.println("Bad project configuration.");
			return;
		}
		
		out.println(viewChapters(request));
	}
	
	public void doPost(HttpServletRequest request,HttpServletResponse response)
			throws ServletException, IOException {
	
		String userRequest = request.getParameter("UserRequest");
		if (userRequest == null) userRequest = "";
		
		switch (userRequest) {
		case "Update":
			updateChapter(request);
			break;
		case "Create":
			createChapter(request);
			break;
		}
		doGet(request,response);
	}

	static void createChapter(HttpServletRequest request) {		
		int chapterNumber = 0;
		try {
			chapterNumber = Integer.parseInt(request.getParameter("ChapterNumber"));
		} catch (Exception e) {}
		Chapter ch = new Chapter(chapterNumber, request.getParameter("Title"), request.getParameter("URL"));
		ofy().save().entity(ch).now();
	}

	static void updateChapter(HttpServletRequest request) {
		long chapterId = 0L;
		try {
			chapterId = Long.parseLong(request.getParameter("ChapterId"));
		} catch (Exception e) {
			return;
		}
		int chapterNumber = 0;
		try {
			chapterNumber = Integer.parseInt(request.getParameter("ChapterNumber"));
		} catch (Exception e) {
			return;
		}
		Chapter ch = ofy().load().type(Chapter.class).id(chapterId).now();
		ch.chapterNumber = chapterNumber;
		ch.title = request.getParameter("Title");
		ch.URL = request.getParameter("URL");
		ofy().save().entity(ch).now();
	}

	static String viewChapters(HttpServletRequest request) {
		StringBuffer buf = new StringBuffer(Util.head);
		
		List<Chapter> chapters = ofy().load().type(Chapter.class).order("chapterNumber").list();
		buf.append("<h1>Manage Chapters</h1>");
		buf.append("<table><tr><th>Chapter</th><th>Title</th><th>URL</th><th>Action</th></tr>");
		for (Chapter ch : chapters) {
			buf.append("<tr>"
					+ "<form method=post>"
					+ "<td><input type=text size=4 name=ChapterNumber value='" + ch.chapterNumber + "' /></td>"
					+ "<td><input type=text size=20 name=Title value='" + ch.title + "' /></td>"
					+ "<td><input type=text size=40 name=URL value='" + ch.URL + "' /></td>"
					+ "<td>"
					+ "<input type=hidden name=ChapterId value=" + ch.id + " />"
					+ "<input type=submit name=UserRequest value='Update' />"
					+ "</td>"
					+ "</form>"
					+ "</tr>");
		}
		// add 1 row to create a new chapter
		buf.append("<tr>"
				+ "<form method=post>"
				+ "<td><input type=text size=4 name=ChapterNumber /></td>"
				+ "<td><input type=text size=20 name=Title /></td>"
				+ "<td><input type=text size=40 name=URL /></td>"
				+ "<td><input type=submit name=UserRequest value='Create' /></td>"
				+ "</form>"
				+ "</tr>");
		buf.append("</table>");
		
		return buf.toString() + Util.foot;
	}
}
