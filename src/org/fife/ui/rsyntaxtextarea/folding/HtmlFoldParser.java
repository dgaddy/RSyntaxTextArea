/*
 * 09/30/2012
 *
 * HtmlFoldParser.java - Fold parser for HTML 5.
 * 
 * This library is distributed under a modified BSD license.  See the included
 * RSyntaxTextArea.License.txt file for details.
 */
package org.fife.ui.rsyntaxtextarea.folding;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;

import javax.swing.text.BadLocationException;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.Token;


/**
 * Fold parser for HTML 5.  We currently don't fold <em>everything</em>
 * possible, just the "big" stuff.
 *
 * @author Robert Futrell
 * @version 1.0
 */
public class HtmlFoldParser implements FoldParser {

	/**
	 * The set of tags we allow to be folded.  These are tags that must have
	 * explicit close tags in both HTML 4 and HTML 5.
	 */
	private static final Set FOLDABLE_TAGS;

	private static final char[] MARKUP_CLOSING_TAG_START = { '<', '/' };
	//private static final char[] MARKUP_SHORT_TAG_END = { '/', '>' };
	private static final char[] MLC_END = { '-', '-', '>' };


	static {
		FOLDABLE_TAGS = new HashSet();
		FOLDABLE_TAGS.add("body");
		FOLDABLE_TAGS.add("canvas");
		FOLDABLE_TAGS.add("div");
		FOLDABLE_TAGS.add("form");
		FOLDABLE_TAGS.add("head");
		FOLDABLE_TAGS.add("html");
		FOLDABLE_TAGS.add("ol");
		FOLDABLE_TAGS.add("pre");
		FOLDABLE_TAGS.add("script");
		FOLDABLE_TAGS.add("span");
		FOLDABLE_TAGS.add("style");
		FOLDABLE_TAGS.add("table");
		FOLDABLE_TAGS.add("tfoot");
		FOLDABLE_TAGS.add("thead");
		FOLDABLE_TAGS.add("tr");
		FOLDABLE_TAGS.add("td");
		FOLDABLE_TAGS.add("ul");
	}


	/**
	 * {@inheritDoc}
	 */
	public List getFolds(RSyntaxTextArea textArea) {

		List folds = new ArrayList();
		Stack tagNameStack = new Stack();

		Fold currentFold = null;
		int lineCount = textArea.getLineCount();
		boolean inMLC = false;
		int mlcStart = 0;
		TagCloseInfo tci = new TagCloseInfo();

		try {

			for (int line=0; line<lineCount; line++) {

				Token t = textArea.getTokenListForLine(line);
				while (t!=null && t.isPaintable()) {

					if (t.isComment()) {

						// Continuing an MLC from a previous line
						if (inMLC) {
							// Found the end of the MLC starting on a previous line...
							if (t.endsWith(MLC_END)) {
								int mlcEnd = t.offset + t.textCount - 1;
								if (currentFold==null) {
									currentFold = new Fold(FoldType.COMMENT, textArea, mlcStart);
									currentFold.setEndOffset(mlcEnd);
									folds.add(currentFold);
									currentFold = null;
								}
								else {
									currentFold = currentFold.createChild(FoldType.COMMENT, mlcStart);
									currentFold.setEndOffset(mlcEnd);
									currentFold = currentFold.getParent();
								}
								inMLC = false;
								mlcStart = 0;
							}
							// Otherwise, this MLC is continuing on to yet
							// another line.
						}

						else {
							// If we're an MLC that ends on a later line...
							if (t.type==Token.COMMENT_MULTILINE && !t.endsWith(MLC_END)) {
								inMLC = true;
								mlcStart = t.offset;
							}
						}

					}

					// If we're starting a new tag...
					else if (t.isSingleChar(Token.MARKUP_TAG_DELIMITER, '<')) {
						Token tagStartToken = t;
						Token tagNameToken = t.getNextToken();
						if (isFoldableTag(tagNameToken)) {
							getTagCloseInfo(tagNameToken, textArea, line, tci);
							if (tci.line==-1) { // EOF reached before end of tag
								return folds;
							}
							// We have found either ">" or "/>" with tci.
							Token tagCloseToken = tci.closeToken;
							if (tagCloseToken.isSingleChar(Token.MARKUP_TAG_DELIMITER, '>')) {
								if (currentFold==null) {
									currentFold = new Fold(FoldType.CODE, textArea, tagStartToken.offset);
									folds.add(currentFold);
								}
								else {
									currentFold = currentFold.createChild(FoldType.CODE, tagStartToken.offset);
								}
								tagNameStack.push(tagNameToken.getLexeme());
							}
							t = tagCloseToken; // Continue parsing after tag
						}
					}

					// If we've found a closing tag (e.g. "</div>").
					else if (t.is(Token.MARKUP_TAG_DELIMITER, MARKUP_CLOSING_TAG_START)) {
						if (currentFold!=null) {
							Token tagNameToken = t.getNextToken();
							if (isFoldableTag(tagNameToken) &&
									isEndOfLastFold(tagNameStack, tagNameToken)) {
								tagNameStack.pop();
								currentFold.setEndOffset(t.offset);
								Fold parentFold = currentFold.getParent();
								// Don't add fold markers for single-line blocks
								if (currentFold.isOnSingleLine()) {
									currentFold.removeFromParent();
								}
								currentFold = parentFold;
								t = tagNameToken;
							}
						}
					}

					t = t.getNextToken();

				}

			}

		} catch (BadLocationException ble) { // Should never happen
			ble.printStackTrace();
		}

		return folds;
	
	}


	/**
	 * Grabs the token representing the closing of a tag (i.e.
	 * "<code>&gt;</code>" or "<code>/&gt;</code>").  This should only be
	 * called after a tag name has been parsed, to ensure  the "closing" of
	 * other tags is not identified.
	 * 
	 * @param tagNameToken The token denoting the name of the tag.
	 * @param textArea The text area whose contents are being parsed.
	 * @param line The line we're currently on.
	 * @param info On return, information about the closing of the tag is
	 *        returned in this object.
	 */
	private void getTagCloseInfo(Token tagNameToken, RSyntaxTextArea textArea,
			int line, TagCloseInfo info) {

		info.reset();
		Token t = tagNameToken.getNextToken();

		do {

			while (t!=null && t.type!=Token.MARKUP_TAG_DELIMITER) {
				t = t.getNextToken();
			}

			if (t!=null) {
				info.closeToken = t;
				info.line = line;
				break;
			}

		} while (++line<textArea.getLineCount() &&
				(t=textArea.getTokenListForLine(line))!=null);

	}


	/**
	 * Returns whether a closing tag ("<code>&lt;/...&gt;</code>") with a
	 * specific name is the closing tag of our current fold region.
	 *
	 * @param tagNameStack The stack of fold regions.
	 * @param tagNameToken The tag name of the most recently parsed closing
	 *        tag.
	 * @return Whether it's the end of the current fold region.
	 */
	private static final boolean isEndOfLastFold(Stack tagNameStack,
			Token tagNameToken) {
		if (tagNameToken!=null && !tagNameStack.isEmpty()) {
			return tagNameToken.getLexeme().equalsIgnoreCase((String)tagNameStack.peek());
		}
		return false;
	}


	/**
	 * Returns whether a tag is one we allow as a foldable region.
	 *
	 * @param tagNameToken The tag's name token.  This may be <code>null</code>.
	 * @return Whether this tag can be a foldable region.
	 */
	private static final boolean isFoldableTag(Token tagNameToken) {
		return tagNameToken!=null &&
				FOLDABLE_TAGS.contains(tagNameToken.getLexeme().toLowerCase());
	}


	/**
	 * A simple wrapper for the token denoting the closing of a tag (i.e.
	 * "<code>&gt;</code>" or "<code>/&gt;</code>").
	 */
	private static class TagCloseInfo {

		private Token closeToken;
		private int line;

		public void reset() {
			closeToken = null;
			line = -1;
		}

		public String toString() {
			return "[TagCloseInfo: " +
					"closeToken=" + closeToken +
					", line=" + line +
					"]";
		}

	}


}