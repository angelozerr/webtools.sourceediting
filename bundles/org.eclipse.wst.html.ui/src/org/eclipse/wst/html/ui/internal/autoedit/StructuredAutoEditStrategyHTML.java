/*******************************************************************************
 * Copyright (c) 2001, 2009 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Jens Lukowski/Innoopract - initial renaming/restructuring
 *     
 *******************************************************************************/
package org.eclipse.wst.html.ui.internal.autoedit;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentCommand;
import org.eclipse.jface.text.IAutoEditStrategy;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;
import org.eclipse.ui.texteditor.ITextEditorExtension3;
import org.eclipse.wst.html.core.internal.contentmodel.HTMLElementDeclaration;
import org.eclipse.wst.html.core.internal.document.HTMLDocumentTypeEntry;
import org.eclipse.wst.html.core.internal.document.HTMLDocumentTypeRegistry;
import org.eclipse.wst.html.core.internal.provisional.HTMLCMProperties;
import org.eclipse.wst.html.ui.internal.HTMLUIPlugin;
import org.eclipse.wst.html.ui.internal.preferences.HTMLUIPreferenceNames;
import org.eclipse.wst.sse.core.StructuredModelManager;
import org.eclipse.wst.sse.core.internal.provisional.IStructuredModel;
import org.eclipse.wst.sse.core.internal.provisional.text.IStructuredDocumentRegion;
import org.eclipse.wst.sse.core.internal.provisional.text.ITextRegion;
import org.eclipse.wst.xml.core.internal.contentmodel.CMElementDeclaration;
import org.eclipse.wst.xml.core.internal.contentmodel.modelquery.ModelQuery;
import org.eclipse.wst.xml.core.internal.modelquery.ModelQueryUtil;
import org.eclipse.wst.xml.core.internal.provisional.document.IDOMDocument;
import org.eclipse.wst.xml.core.internal.provisional.document.IDOMElement;
import org.eclipse.wst.xml.core.internal.provisional.document.IDOMNode;
import org.eclipse.wst.xml.core.internal.regions.DOMRegionContext;
import org.eclipse.wst.xml.ui.internal.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Automatically inserts closing comment tag or end tag when appropriate.
 */
public class StructuredAutoEditStrategyHTML implements IAutoEditStrategy {
	/*
	 * NOTE: copies of this class exists in
	 * org.eclipse.wst.xml.ui.internal.autoedit
	 * org.eclipse.wst.html.ui.internal.autoedit
	 */
	public void customizeDocumentCommand(IDocument document, DocumentCommand command) {
		Object textEditor = getActiveTextEditor();
		if (!(textEditor instanceof ITextEditorExtension3 && ((ITextEditorExtension3) textEditor).getInsertMode() == ITextEditorExtension3.SMART_INSERT))
			return;

		IStructuredModel model = null;
		try {
			model = StructuredModelManager.getModelManager().getExistingModelForRead(document);
			if (model != null) {
				if (command.text != null) {
					smartInsertCloseElement(command, document, model);
					smartInsertForComment(command, document, model);
					smartInsertForEndTag(command, document, model);
					smartRemoveEndTag(command, document, model);
				}
			}
		}
		finally {
			if (model != null)
				model.releaseFromRead();
		}
	}

	private boolean isPreferenceEnabled(String key) {
		return (key != null && HTMLUIPlugin.getDefault().getPreferenceStore().getBoolean(key));
	}
	
	private boolean isCommentNode(IDOMNode node) {
		return (node != null && node instanceof IDOMElement && ((IDOMElement) node).isCommentTag());
	}

	private boolean isDocumentNode(IDOMNode node) {
		return (node != null && node.getNodeType() == Node.DOCUMENT_NODE);
	}
	
	/**
	 * Attempts to clean up an end-tag if a start-tag is converted into an empty-element
	 * tag (e.g., <node />) and the original element was empty.
	 * 
	 * @param command the document command describing the change
	 * @param document the document that will be changed
	 * @param model the model based on the document
	 */
	private void smartRemoveEndTag(DocumentCommand command, IDocument document, IStructuredModel model) {
		try {
			// An opening tag is now a self-terminated end-tag
			if ("/".equals(command.text) && ">".equals(document.get(command.offset, 1)) && isPreferenceEnabled(HTMLUIPreferenceNames.TYPING_REMOVE_END_TAGS)) { //$NON-NLS-1$ //$NON-NLS-2$
				IDOMNode node = (IDOMNode) model.getIndexedRegion(command.offset);
				if (node != null && !node.hasChildNodes()) {
					IStructuredDocumentRegion region = node.getFirstStructuredDocumentRegion();
					if(region.getFirstRegion().getType() == DOMRegionContext.XML_TAG_OPEN && command.offset <= region.getEnd()) {
						region = node.getEndStructuredDocumentRegion();

						if (region != null && region.isEnded())
							document.replace(region.getStartOffset(), region.getLength(), ""); //$NON-NLS-1$
					}
				}
			}
		}
		catch (BadLocationException e) {
			Logger.logException(e);
		}
	}

	private void smartInsertForComment(DocumentCommand command, IDocument document, IStructuredModel model) {
		try {
			if (command.text.equals("-") && document.getLength() >= 3 && document.get(command.offset - 3, 3).equals("<!-") && isPreferenceEnabled(HTMLUIPreferenceNames.TYPING_COMPLETE_COMMENTS)) { //$NON-NLS-1$ //$NON-NLS-2$
				command.text += "  -->"; //$NON-NLS-1$
				command.shiftsCaret = false;
				command.caretOffset = command.offset + 2;
				command.doit = false;
			}
		}
		catch (BadLocationException e) {
			Logger.logException(e);
		}

	}
	
	/**
	 * Attempts to insert the end tag when completing a start-tag with the '&gt;' character.
	 * 
	 * @param command
	 * @param document
	 * @param model
	 */
	private void smartInsertCloseElement(DocumentCommand command, IDocument document, IStructuredModel model) {
		try {
			// Check terminating start tag, but ignore empty-element tags
			if (command.text.equals(">") && document.getLength() > 0 && document.getChar(command.offset - 1) != '/' && isPreferenceEnabled(HTMLUIPreferenceNames.TYPING_COMPLETE_ELEMENTS)) { //$NON-NLS-1$
				IDOMNode node = (IDOMNode) model.getIndexedRegion(command.offset - 1);
				boolean isClosedByParent = false;
				// Only insert an end-tag if necessary. Because of the way the document is parsed, it is possible for a child tag with the same
				// name as an ancestor to be paired with the end-tag of an ancestor, so the ancestors must be checked for an unclosed tag.
				if (node != null && node.getNodeType() == Node.ELEMENT_NODE && (!node.isClosed() || (isClosedByParent = hasUnclosedAncestor(node)))) {
					IStructuredDocumentRegion region = node.getEndStructuredDocumentRegion();
					if (region != null && region.getRegions().size() > 0 && region.getRegions().get(0).getType() == DOMRegionContext.XML_END_TAG_OPEN && !isClosedByParent)
						return;
					CMElementDeclaration decl = getCMElementDeclaration(node);
					// If it's XHTML, always generate the end tag
					if (isXHTML(node) || shouldGenerateEndTag(decl)) {
						command.text += "</" + getElementName(node, command.offset) + ">"; //$NON-NLS-1$ //$NON-NLS-2$
						command.shiftsCaret = false;
						command.caretOffset = command.offset + 1;
					}
				}
				
			}
		} catch (BadLocationException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Get the element name that will be created by closing the start tag. Defaults
	 * to the node's nodeName.
	 * @param node the node that is being edited
	 * @param offset the offset in the document where the start tag is closed
	 * @return The element name of the tag
	 */
	private String getElementName(IDOMNode node, int offset) {
		String name = null;
		
		IStructuredDocumentRegion region = node.getFirstStructuredDocumentRegion();
		ITextRegion textRegion = region.getRegionAtCharacterOffset(offset);
		if (textRegion != null && textRegion.getType() == DOMRegionContext.XML_TAG_NAME) {
			int nameStart = region.getStartOffset(textRegion);
			String regionText = region.getText(textRegion);
			int length = offset - nameStart;
			if (length <= regionText.length())
				name = regionText.substring(0, length);
		}
		
		// Default to the node name
		if (name == null)
			name = node.getNodeName();
		return name;
	}

	private void smartInsertForEndTag(DocumentCommand command, IDocument document, IStructuredModel model) {
		try {
			if (command.text.equals("/") && document.getLength() >= 1 && document.get(command.offset - 1, 1).equals("<") && isPreferenceEnabled(HTMLUIPreferenceNames.TYPING_COMPLETE_END_TAGS)) { //$NON-NLS-1$ //$NON-NLS-2$
				IDOMNode parentNode = (IDOMNode) ((IDOMNode) model.getIndexedRegion(command.offset - 1)).getParentNode();
				if (isCommentNode(parentNode)) {
					// loop and find non comment node parent
					while (parentNode != null && isCommentNode(parentNode)) {
						parentNode = (IDOMNode) parentNode.getParentNode();
					}
				}

				if (!isDocumentNode(parentNode)) {
					// only add end tag if one does not already exist or if
					// add '/' does not create one already
					IStructuredDocumentRegion endTagStructuredDocumentRegion = parentNode.getEndStructuredDocumentRegion();
					if (endTagStructuredDocumentRegion == null) {
						StringBuffer toAdd = new StringBuffer(parentNode.getNodeName());
						if (toAdd.length() > 0) {
							toAdd.append(">"); //$NON-NLS-1$
							String suffix = toAdd.toString();
							if ((document.getLength() < command.offset + suffix.length()) || (!suffix.equals(document.get(command.offset, suffix.length())))) {
								command.text += suffix;
							}
						}
					}
				}
			}
		}
		catch (BadLocationException e) {
			Logger.logException(e);
		}
	}

	/**
	 * Checks if <code>node</code> has an unclosed ancestor by the same name
	 * 
	 * @param node the node to check
	 * @return true if <code>node</code> has an unclosed parent with the same node name
	 */
	private boolean hasUnclosedAncestor(IDOMNode node) {
		IDOMNode parent = (IDOMNode) node.getParentNode();
		while (parent != null && parent.getNodeType() != Node.DOCUMENT_NODE && parent.getNodeName().equals(node.getNodeName())) {
			if (!parent.isClosed())
				return true;
			parent = (IDOMNode) parent.getParentNode();
		}
		return false;
	}

	/**
	 * Based on the content model, determine if an end tag should be generated
	 * @param elementDecl the content model element declaration
	 * @return true if the end tag should be generated; false otherwise.
	 */
	private boolean shouldGenerateEndTag(CMElementDeclaration elementDecl) {
		if (elementDecl == null)
			return false;
		if (elementDecl instanceof HTMLElementDeclaration) {
			if (((Boolean) elementDecl.getProperty(HTMLCMProperties.IS_JSP)).booleanValue()) {
				if (elementDecl.getContentType() == CMElementDeclaration.EMPTY)
					return false;
			}
			else {
				String ommission = (String) elementDecl.getProperty(HTMLCMProperties.OMIT_TYPE);
				if (ommission.equals(HTMLCMProperties.Values.OMIT_END) || ommission.equals(HTMLCMProperties.Values.OMIT_END_DEFAULT) || ommission.equals(HTMLCMProperties.Values.OMIT_END_MUST)) {
					return false;
				}
			}
		}

		if (elementDecl.getContentType() == CMElementDeclaration.EMPTY)
			return false;
		return true;
	}

	private CMElementDeclaration getCMElementDeclaration(Node node) {
		CMElementDeclaration result = null;
		if (node.getNodeType() == Node.ELEMENT_NODE) {
			ModelQuery modelQuery = ModelQueryUtil.getModelQuery(node.getOwnerDocument());
			if (modelQuery != null) {
				result = modelQuery.getCMElementDeclaration((Element) node);
			}
		}
		return result;
	}

	/**
	 * Is the node part of an XHTML document
	 * @param node
	 * @return
	 */
	private boolean isXHTML(Node node) {
		Document doc = node.getOwnerDocument();
		if (!(doc instanceof IDOMDocument))
			return false;
		String typeid = ((IDOMDocument) doc).getDocumentTypeId();
		if (typeid != null) {
			HTMLDocumentTypeEntry entry = HTMLDocumentTypeRegistry.getInstance().getEntry(typeid);
			return (entry != null && entry.isXMLType());
		}
		return false;
	}
	
	/**
	 * Return the active text editor if possible, otherwise the active editor
	 * part.
	 * 
	 * @return Object
	 */
	private Object getActiveTextEditor() {
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		if (window != null) {
			IWorkbenchPage page = window.getActivePage();
			if (page != null) {
				IEditorPart editor = page.getActiveEditor();
				if (editor != null) {
					if (editor instanceof ITextEditor)
						return editor;
					ITextEditor textEditor = (ITextEditor) editor.getAdapter(ITextEditor.class);
					if (textEditor != null)
						return textEditor;
					return editor;
				}
			}
		}
		return null;
	}
}