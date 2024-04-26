/*  ChemVantage - A Java web application for online learning
*   Copyright (C) 2011 ChemVantage LLC
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

import static com.googlecode.objectify.ObjectifyService.key;

import com.google.gson.JsonObject;
import com.googlecode.objectify.Key;
import com.googlecode.objectify.annotation.Entity;
import com.googlecode.objectify.annotation.Id;
import com.googlecode.objectify.annotation.Index;
import com.googlecode.objectify.annotation.Parent;

@Entity
public class Score {    // this object represents a best score achieved by a user on a quiz or homework
	@Parent Key<User> owner;
	@Id	Long id;
	@Index	int score = 0;
	@Index Long conceptId;
	Long questionId = null;
	boolean gotHelp = false;
	
	Score() {}
	
	Score(String hashedId, Long conceptId) {
		this.owner = key(User.class,hashedId);
		this.id = conceptId;
		this.conceptId = conceptId;
	}
	
	boolean update(JsonObject questionScore) throws Exception {
		int oldQuintileRank = score/20 + 1;
		int rawScore = questionScore.get("rawScore").getAsInt();
		int proposedScore = 0;  // range 0-100 percent
		/*
		 * Apply this scoring algorithm to update the user's Score on the current Concept:
		 * If the user got help from Sage:
		 *   prior score < 50 - add 5*rawScore (0, 5 or 10 points)
		 *   prior score > 50 - subtract 5*(2-rawScore)
		 * Otherwise, if the user got no help:
		 *   q = user’s quintile (1-5) based on current score
		 *   rawScore (0,1 or 2) - a 1 means partially or almost correct
		 *   n = (17-2q)/3 averaging constant - range 2-5 
		 *   Sn = (60c + nSn-1)/(n+1)  stars (100 max = 83.3% proficient)
		 *   If (c<2) the minimum quintile rank score is a floor for the user (0, 20, 40, 60, 80).
		 */
		if (gotHelp) {
			proposedScore = score + 5*rawScore - (score<50?0:10);
		} else {
			int n = (17-2*oldQuintileRank)/3;
			proposedScore = (60*rawScore + n*score)/(n+1);
			if (proposedScore > 100) proposedScore = 100;
		}
		
		// Check for any changes in quintile rank and apply guardrails, if needed:
		int newQuintileRank = proposedScore/20 + 1;
		if (newQuintileRank < oldQuintileRank) proposedScore = (oldQuintileRank - 1)*20;  // minimum score
		
		// return the updated Score object
		score = proposedScore;
		questionId = proposedScore==100?null:Sage.getNewQuestionId(this);
		gotHelp =  false;
		if (conceptId==null) conceptId = id;
		
		return newQuintileRank > oldQuintileRank;
	}
	

}