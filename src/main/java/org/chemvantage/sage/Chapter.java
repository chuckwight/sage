/*  ChemVantage - A Java web application for online learning
*   Copyright (C) 2022 ChemVantage LLC
*   
*    This program is free software: you can redistribute it and/or modify
*   it under the terms of the GNU General Public License as published by
*   the Free Software Foundation, either version 3 of the License, or
*   (at your option) any later version.
*
*   This program is distributed in the hope that it will be useful,
*   but WITHOUT ANY WARRANTY; without even the implied warranty of
*   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
*   GNU General Public License for more details.
*
*   You should have received a copy of the GNU General Public License
*   along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package org.chemvantage.sage;

/*
 * This entity represents a chapter in a General Chemistry textbook, e.g. OpenStax Chemistry/2e.
 * Typically, we can expect 4-8 Concepts per chapter in a General Chemistry textbook.
 * Questions have a field of one conceptId value, so they can be filtered in a query.
 */
import java.io.Serializable;

import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;

@Entity
public class Chapter implements Serializable {
	private static final long serialVersionUID = 137L;
	@Id 	Long id;
	@Index 	int chapterNumber;
	 		String title;
	 		String URL;
	
	Chapter() {}

	Chapter(int chapterNumber,String title,String URL) {
		this.title = title;
		this.chapterNumber = chapterNumber;
		this.URL = URL;
	}
}
