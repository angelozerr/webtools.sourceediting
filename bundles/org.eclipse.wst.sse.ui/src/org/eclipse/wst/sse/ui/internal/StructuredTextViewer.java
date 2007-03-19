/*******************************************************************************
 * Copyright (c) 2001, 2007 IBM Corporation and others.
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
package org.eclipse.wst.sse.ui.internal;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentAdapter;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.ITextViewerExtension2;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.text.contentassist.ContentAssistant;
import org.eclipse.jface.text.contentassist.IContentAssistant;
import org.eclipse.jface.text.formatter.FormattingContext;
import org.eclipse.jface.text.formatter.FormattingContextProperties;
import org.eclipse.jface.text.formatter.IContentFormatterExtension;
import org.eclipse.jface.text.formatter.IFormattingContext;
import org.eclipse.jface.text.hyperlink.IHyperlinkDetector;
import org.eclipse.jface.text.information.IInformationPresenter;
import org.eclipse.jface.text.projection.ProjectionDocument;
import org.eclipse.jface.text.reconciler.IReconciler;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IOverviewRuler;
import org.eclipse.jface.text.source.IVerticalRuler;
import org.eclipse.jface.text.source.SourceViewerConfiguration;
import org.eclipse.jface.text.source.projection.ProjectionViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.IEditorStatusLine;
import org.eclipse.wst.sse.core.internal.cleanup.StructuredContentCleanupHandler;
import org.eclipse.wst.sse.core.internal.provisional.text.IStructuredDocument;
import org.eclipse.wst.sse.core.internal.undo.IDocumentSelectionMediator;
import org.eclipse.wst.sse.core.internal.undo.IStructuredTextUndoManager;
import org.eclipse.wst.sse.core.internal.undo.UndoDocumentEvent;
import org.eclipse.wst.sse.ui.StructuredTextViewerConfiguration;
import org.eclipse.wst.sse.ui.internal.provisional.style.Highlighter;
import org.eclipse.wst.sse.ui.internal.provisional.style.LineStyleProvider;
import org.eclipse.wst.sse.ui.internal.reconcile.StructuredRegionProcessor;
import org.eclipse.wst.sse.ui.internal.util.PlatformStatusLineUtil;

public class StructuredTextViewer extends ProjectionViewer implements IDocumentSelectionMediator {
	/** Text operation codes */
	private static final int BASE = ProjectionViewer.COLLAPSE_ALL; // see
	// ProjectionViewer.COLLAPSE_ALL
	private static final int CLEANUP_DOCUMENT = BASE + 1;
	public static final int FORMAT_ACTIVE_ELEMENTS = BASE + 3;

	private static final String FORMAT_ACTIVE_ELEMENTS_TEXT = SSEUIMessages.Format_Active_Elements_UI_; //$NON-NLS-1$
	public static final int FORMAT_DOCUMENT = BASE + 2;
	private static final String FORMAT_DOCUMENT_TEXT = SSEUIMessages.Format_Document_UI_; //$NON-NLS-1$
	public static final int QUICK_FIX = BASE + 4;
	private static final String TEXT_CUT = SSEUIMessages.Text_Cut_UI_; //$NON-NLS-1$
	private static final String TEXT_PASTE = SSEUIMessages.Text_Paste_UI_; //$NON-NLS-1$
	private static final String TEXT_SHIFT_LEFT = SSEUIMessages.Text_Shift_Left_UI_; //$NON-NLS-1$ = "Text Shift Left"
	private static final String TEXT_SHIFT_RIGHT = SSEUIMessages.Text_Shift_Right_UI_; //$NON-NLS-1$ = "Text Shift Right"
	private static final boolean TRACE_EXCEPTIONS = true;

	private boolean fBackgroundupdateInProgress;
	private StructuredContentCleanupHandler fContentCleanupHandler = null;
	private IContentAssistant fCorrectionAssistant;
	private boolean fCorrectionAssistantInstalled;
	private IDocumentAdapter fDocAdapter;

	private Highlighter fHighlighter;

	// private ViewerSelectionManager fViewerSelectionManager;
	private SourceViewerConfiguration fConfiguration;

	/**
	 * @see org.eclipse.jface.text.source.SourceViewer#SourceViewer(Composite,
	 *      IVerticalRuler, IOverviewRuler, boolean, int)
	 */
	public StructuredTextViewer(Composite parent, IVerticalRuler verticalRuler, IOverviewRuler overviewRuler, boolean showAnnotationsOverview, int styles) {
		super(parent, verticalRuler, overviewRuler, showAnnotationsOverview, styles);
	}

	/**
	 * 
	 */
	private void beep() {
		getTextWidget().getDisplay().beep();
	}

	public void beginBackgroundUpdate() {
		fBackgroundupdateInProgress = true;
		setRedraw(false);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.jface.text.ITextOperationTarget#canDoOperation(int)
	 */
	public boolean canDoOperation(int operation) {
		if (fBackgroundupdateInProgress) {
			return false;
		}
		switch (operation) {
			case CONTENTASSIST_PROPOSALS : {
				// (pa) if position isn't READ_ONLY (containsReadOnly()
				// returns false),
				// Otherwise, you DO want content assist (return true)
				IDocument doc = getDocument();
				if (doc != null && doc instanceof IStructuredDocument) {
					return isEditable() && (!((IStructuredDocument) doc).containsReadOnly(getSelectedRange().x, 0));
				}
				break;
			}
			case QUICK_FIX : {
				return isEditable();
			}
			case CLEANUP_DOCUMENT : {
				return (fContentCleanupHandler != null && isEditable());
			}
			case FORMAT_DOCUMENT :
			case FORMAT_ACTIVE_ELEMENTS : {
				return (fContentFormatter != null && isEditable());
			}
		}
		return super.canDoOperation(operation);
	}

	/**
	 * Should be identical to superclass version. Plus, we get our own special
	 * Highlighter. Plus we uninstall before installing.
	 */
	public void configure(SourceViewerConfiguration configuration) {

		if (getTextWidget() == null)
			return;

		setDocumentPartitioning(configuration.getConfiguredDocumentPartitioning(this));

		// always uninstall highlighter and null it out on new configuration
		if (fHighlighter != null) {
			fHighlighter.uninstall();
			fHighlighter = null;
		}

		// install content type independent plugins
		if (fPresentationReconciler != null)
			fPresentationReconciler.uninstall();
		fPresentationReconciler = configuration.getPresentationReconciler(this);
		if (fPresentationReconciler != null)
			fPresentationReconciler.install(this);

		IReconciler newReconciler = configuration.getReconciler(this);

		if (newReconciler != fReconciler || newReconciler == null || fReconciler == null) {

			if (fReconciler != null) {
				fReconciler.uninstall();
			}

			fReconciler = newReconciler;

			if (fReconciler != null) {
				fReconciler.install(this);
				// https://w3.opensource.ibm.com/bugzilla/show_bug.cgi?id=3858
				// still need set document on the reconciler (strategies)
				((StructuredRegionProcessor) fReconciler).setDocument(getDocument());
			}
		}

		IContentAssistant newAssistant = configuration.getContentAssistant(this);
		if (newAssistant != fContentAssistant || newAssistant == null || fContentAssistant == null) {
			if (fContentAssistant != null)
				fContentAssistant.uninstall();

			fContentAssistant = newAssistant;

			if (fContentAssistant != null) {
				fContentAssistant.install(this);
				fContentAssistantInstalled = true;
			}
			else {
				// 248036
				// disable the content assist operation if no content
				// assistant
				enableOperation(CONTENTASSIST_PROPOSALS, false);
			}
		}

		fContentFormatter = configuration.getContentFormatter(this);

		// do not uninstall old information presenter if it's the same
		IInformationPresenter newInformationPresenter = configuration.getInformationPresenter(this);
		if (newInformationPresenter == null || fInformationPresenter == null || !(newInformationPresenter.equals(fInformationPresenter))) {
			if (fInformationPresenter != null)
				fInformationPresenter.uninstall();
			fInformationPresenter = newInformationPresenter;
			if (fInformationPresenter != null)
				fInformationPresenter.install(this);
		}

		// disconnect from the old undo manager before setting the new one
		if (fUndoManager != null) {
			fUndoManager.disconnect();
		}
		setUndoManager(configuration.getUndoManager(this));

		// release old annotation hover before setting new one
		if (fAnnotationHover instanceof StructuredTextAnnotationHover) {
			((StructuredTextAnnotationHover) fAnnotationHover).release();
		}
		setAnnotationHover(configuration.getAnnotationHover(this));

		// release old annotation hover before setting new one
		if (fOverviewRulerAnnotationHover instanceof StructuredTextAnnotationHover) {
			((StructuredTextAnnotationHover) fOverviewRulerAnnotationHover).release();
		}
		setOverviewRulerAnnotationHover(configuration.getAnnotationHover(this));

		getTextWidget().setTabs(configuration.getTabWidth(this));
		setHoverControlCreator(configuration.getInformationControlCreator(this));

		// if hyperlink manager has already been created, uninstall it
		if (fHyperlinkManager != null) {
			setHyperlinkDetectors(null, SWT.NONE);
		}
		setHyperlinkPresenter(configuration.getHyperlinkPresenter(this));
		IHyperlinkDetector[] hyperlinkDetectors = configuration.getHyperlinkDetectors(this);
		int eventStateMask = configuration.getHyperlinkStateMask(this);
		setHyperlinkDetectors(hyperlinkDetectors, eventStateMask);

		// install content type specific plugins
		String[] types = configuration.getConfiguredContentTypes(this);

		// clear autoindent/autoedit strategies
		fAutoIndentStrategies = null;
		for (int i = 0; i < types.length; i++) {
			String t = types[i];
			setAutoEditStrategies(configuration.getAutoEditStrategies(this, t), t);
			setTextDoubleClickStrategy(configuration.getDoubleClickStrategy(this, t), t);

			int[] stateMasks = configuration.getConfiguredTextHoverStateMasks(this, t);
			if (stateMasks != null) {
				for (int j = 0; j < stateMasks.length; j++) {
					int stateMask = stateMasks[j];
					setTextHover(configuration.getTextHover(this, t, stateMask), t, stateMask);
				}
			}
			else {
				setTextHover(configuration.getTextHover(this, t), t, ITextViewerExtension2.DEFAULT_HOVER_STATE_MASK);
			}

			String[] prefixes = configuration.getIndentPrefixes(this, t);
			if (prefixes != null && prefixes.length > 0)
				setIndentPrefixes(prefixes, t);

			prefixes = configuration.getDefaultPrefixes(this, t);
			if (prefixes != null && prefixes.length > 0)
				setDefaultPrefixes(prefixes, t);

			// add highlighter/linestyleprovider
			// BUG139753 - only create Highlighter if we have a valid document
			if (configuration instanceof StructuredTextViewerConfiguration && getDocument() instanceof IStructuredDocument) {
				LineStyleProvider[] providers = ((StructuredTextViewerConfiguration) configuration).getLineStyleProviders(this, t);
				if (providers != null) {
					for (int j = 0; j < providers.length; ++j) {
						// delay creation of highlighter till
						// linestyleprovider needs to be added
						if (fHighlighter == null)
							fHighlighter = new Highlighter();
						fHighlighter.addProvider(t, providers[j]);
					}
				}
			}
		}

		// initialize highlighter after linestyleproviders were added
		if (fHighlighter != null) {
			fHighlighter.setDocumentPartitioning(configuration.getConfiguredDocumentPartitioning(this));
			fHighlighter.setDocument((IStructuredDocument) getDocument());
			fHighlighter.install(this);
		}

		activatePlugins();

		fConfiguration = configuration;
	}

	/**
	 * @param document
	 * @param startOffset
	 * @param endOffset
	 * @return
	 */
	private boolean containsReadOnly(IDocument document, int startOffset, int endOffset) {

		int start = startOffset;
		int end = endOffset;
		IStructuredDocument structuredDocument = null;
		if (document instanceof IStructuredDocument) {
			structuredDocument = (IStructuredDocument) document;
		}
		else {
			if (document instanceof ProjectionDocument) {
				IDocument doc = ((ProjectionDocument) document).getMasterDocument();
				if (doc instanceof IStructuredDocument) {
					structuredDocument = (IStructuredDocument) doc;
					int adjust = ((ProjectionDocument) document).getProjectionMapping().getCoverage().getOffset();
					start = adjust + start;
					end = adjust + end;
				}
			}
		}
		if (structuredDocument == null) {
			return false;
		}
		else {
			int length = end - start;
			return structuredDocument.containsReadOnly(start, length);
		}
	}

	protected IDocumentAdapter createDocumentAdapter() {

		fDocAdapter = new StructuredDocumentToTextAdapter(getTextWidget());
		return fDocAdapter;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.jface.text.ITextOperationTarget#doOperation(int)
	 */
	public void doOperation(int operation) {

		Point selection = getTextWidget().getSelection();
		int cursorPosition = selection.x;
		int selectionLength = selection.y - selection.x;
		switch (operation) {
			case CUT :
				beginRecording(TEXT_CUT, TEXT_CUT, cursorPosition, selectionLength);
				super.doOperation(operation);
				selection = getTextWidget().getSelection();
				cursorPosition = selection.x;
				selectionLength = selection.y - selection.x;
				endRecording(cursorPosition, selectionLength);
				break;
			case PASTE :
				beginRecording(TEXT_PASTE, TEXT_PASTE, cursorPosition, selectionLength);
				super.doOperation(operation);
				selection = getTextWidget().getSelection();
				cursorPosition = selection.x;
				selectionLength = selection.y - selection.x;
				endRecording(cursorPosition, selectionLength);
				break;
			case CONTENTASSIST_PROPOSALS :
				// maybe not configured?
				if (fContentAssistant != null && isEditable()) {
					// CMVC 263269
					// need an explicit check here because the
					// contentAssistAction is no longer being updated on
					// cursor
					// position
					if (canDoOperation(CONTENTASSIST_PROPOSALS)) {
						String err = fContentAssistant.showPossibleCompletions();
						if (err != null) {
							// don't wanna beep if there is no error
							PlatformStatusLineUtil.displayErrorMessage(err);
						}
						PlatformStatusLineUtil.addOneTimeClearListener();
					}
					else
						beep();
				}
				break;
			case CONTENTASSIST_CONTEXT_INFORMATION :
				if (fContentAssistant != null) {
					String err = fContentAssistant.showContextInformation();
					PlatformStatusLineUtil.displayErrorMessage(err);
					PlatformStatusLineUtil.addOneTimeClearListener();
					// setErrorMessage(err);
					// new OneTimeListener(getTextWidget(), new
					// ClearErrorMessage());
				}
				break;
			case QUICK_FIX :
				if (isEditable() && fCorrectionAssistant != null && fCorrectionAssistantInstalled) {
					String msg = fCorrectionAssistant.showPossibleCompletions();
					setErrorMessage(msg);
				}
				break;
			case SHIFT_RIGHT :
				beginRecording(TEXT_SHIFT_RIGHT, TEXT_SHIFT_RIGHT, cursorPosition, selectionLength);
				updateIndentationPrefixes();
				super.doOperation(SHIFT_RIGHT);
				selection = getTextWidget().getSelection();
				cursorPosition = selection.x;
				selectionLength = selection.y - selection.x;
				endRecording(cursorPosition, selectionLength);
				break;
			case SHIFT_LEFT :
				beginRecording(TEXT_SHIFT_LEFT, TEXT_SHIFT_LEFT, cursorPosition, selectionLength);
				updateIndentationPrefixes();
				super.doOperation(SHIFT_LEFT);
				selection = getTextWidget().getSelection();
				cursorPosition = selection.x;
				selectionLength = selection.y - selection.x;
				endRecording(cursorPosition, selectionLength);
				break;
			case FORMAT_DOCUMENT :
				try {
					/*
					 * This command will actually format selection if text is
					 * selected, otherwise format entire document
					 */
					// begin recording
					beginRecording(FORMAT_DOCUMENT_TEXT, FORMAT_DOCUMENT_TEXT, cursorPosition, selectionLength);
					boolean formatDocument = false;
					IRegion region = null;
					Point s = getSelectedRange();
					if (s.y > 0) {
						// only format currently selected text
						region = new Region(s.x, s.y);
					}
					else {
						// no selection, so format entire document
						region = getModelCoverage();
						formatDocument = true;
					}
					if (fContentFormatter instanceof IContentFormatterExtension) {
						IContentFormatterExtension extension = (IContentFormatterExtension) fContentFormatter;
						IFormattingContext context = new FormattingContext();
						context.setProperty(FormattingContextProperties.CONTEXT_DOCUMENT, Boolean.valueOf(formatDocument));
						context.setProperty(FormattingContextProperties.CONTEXT_REGION, region);
						extension.format(getDocument(), context);
					}
					else {
						fContentFormatter.format(getDocument(), region);
					}
				}
				finally {
					// end recording
					selection = getTextWidget().getSelection();
					cursorPosition = selection.x;
					selectionLength = selection.y - selection.x;
					endRecording(cursorPosition, selectionLength);
				}
				break;
			case FORMAT_ACTIVE_ELEMENTS :
				try {
					/*
					 * This command will format the node at cursor position
					 * (and all its children)
					 */
					// begin recording
					beginRecording(FORMAT_ACTIVE_ELEMENTS_TEXT, FORMAT_ACTIVE_ELEMENTS_TEXT, cursorPosition, selectionLength);
					IRegion region = null;
					Point s = getSelectedRange();
					if (s.y > -1) {
						// only format node at cursor position
						region = new Region(s.x, s.y);
					}
					if (fContentFormatter instanceof IContentFormatterExtension) {
						IContentFormatterExtension extension = (IContentFormatterExtension) fContentFormatter;
						IFormattingContext context = new FormattingContext();
						context.setProperty(FormattingContextProperties.CONTEXT_DOCUMENT, Boolean.FALSE);
						context.setProperty(FormattingContextProperties.CONTEXT_REGION, region);
						extension.format(getDocument(), context);
					}
					else {
						fContentFormatter.format(getDocument(), region);
					}
				}
				finally {
					// end recording
					selection = getTextWidget().getSelection();
					cursorPosition = selection.x;
					selectionLength = selection.y - selection.x;
					endRecording(cursorPosition, selectionLength);
				}
				break;
			default :
				super.doOperation(operation);
		}
	}

	private void endRecording(int cursorPosition, int selectionLength) {
		IDocument doc = getDocument();
		if (doc instanceof IStructuredDocument) {
			IStructuredDocument structuredDocument = (IStructuredDocument) doc;
			IStructuredTextUndoManager undoManager = structuredDocument.getUndoManager();
			undoManager.endRecording(this, cursorPosition, selectionLength);
		}
		else {
			// TODO: how to handle other document types?
		}
	}

	private void beginRecording(String label, String description, int cursorPosition, int selectionLength) {
		IDocument doc = getDocument();
		if (doc instanceof IStructuredDocument) {
			IStructuredDocument structuredDocument = (IStructuredDocument) doc;
			IStructuredTextUndoManager undoManager = structuredDocument.getUndoManager();
			undoManager.beginRecording(this, label, description, cursorPosition, selectionLength);
		}
		else {
			// TODO: how to handle other document types?
		}
	}

	public void endBackgroundUpdate() {
		fBackgroundupdateInProgress = false;
		setRedraw(true);
	}

	protected void handleDispose() {
		Logger.trace("Source Editor", "StructuredTextViewer::handleDispose entry"); //$NON-NLS-1$ //$NON-NLS-2$

		// before we dispose, we set a special "empty" selection, to prevent
		// the "leak one document" that
		// otherwise occurs when editor closed (since last selection stays in
		// SelectedResourceManager.
		// the occurance of the "leak" isn't so bad, but makes debugging other
		// leaks very hard.
		setSelection(TextSelection.emptySelection());

		if (fHighlighter != null) {
			fHighlighter.uninstall();
			fHighlighter = null;
		}
		super.handleDispose();

		Logger.trace("Source Editor", "StructuredTextViewer::handleDispose exit"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/*
	 * Overridden for special support of background update and read-only
	 * regions
	 */
	protected void handleVerifyEvent(VerifyEvent e) {
		IRegion modelRange = event2ModelRange(e);
		if (exposeModelRange(modelRange)) {
			e.doit = false;
			return;
		}

		if (fEventConsumer != null) {
			fEventConsumer.processEvent(e);
			if (!e.doit)
				return;
		}
		if (fBackgroundupdateInProgress) {
			e.doit = false;
			beep();
			return;
		}
		// for read-only support
		if (containsReadOnly(getVisibleDocument(), e.start, e.end)) {
			e.doit = false;
			beep();
			return;
		}

		try {
			super.handleVerifyEvent(e);
		}
		catch (Exception x) {
			// note, we catch and log any exception,
			// since an otherwise can actually prevent typing!
			// see https://bugs.eclipse.org/bugs/show_bug.cgi?id=111318

			if (TRACE_EXCEPTIONS)
				Logger.logException("StructuredTextViewer.exception.verifyText", x); //$NON-NLS-1$

		}
	}

	public int modelLine2WidgetLine(int modelLine) {
		/**
		 * need to override this method as a workaround for Bug 85709
		 */
		if (fInformationMapping == null) {
			IDocument document = getDocument();
			if (document != null) {
				try {
					IRegion modelLineRegion = getDocument().getLineInformation(modelLine);
					IRegion region = getModelCoverage();
					if (modelLineRegion != null && region != null) {
						int modelEnd = modelLineRegion.getOffset() + modelLineRegion.getLength();
						int regionEnd = region.getOffset() + region.getLength();
						// returns -1 if modelLine is invalid
						if ((modelLineRegion.getOffset() < region.getOffset()) || (modelEnd > regionEnd))
							return -1;
					}
				}
				catch (BadLocationException e) {
					// returns -1 if modelLine is invalid
					return -1;
				}
			}
		}
		return super.modelLine2WidgetLine(modelLine);
	}

	public int modelOffset2WidgetOffset(int modelOffset) {
		/**
		 * need to override this method as a workaround for Bug 85709
		 */
		if (fInformationMapping == null) {
			IRegion region = getModelCoverage();
			if (region != null) {
				// returns -1 if modelOffset is invalid
				if (modelOffset < region.getOffset() || modelOffset > (region.getOffset() + region.getLength()))
					return -1;
			}
		}
		return super.modelOffset2WidgetOffset(modelOffset);
	}

	public IRegion modelRange2WidgetRange(IRegion modelRange) {
		// need to override this method as workaround for Bug85709
		if (fInformationMapping == null) {
			IRegion region = getModelCoverage();
			if (region != null && modelRange != null) {
				int modelEnd = modelRange.getOffset() + modelRange.getLength();
				int regionEnd = region.getOffset() + region.getLength();
				// returns null if modelRange is invalid
				if ((modelRange.getOffset() < region.getOffset()) || (modelEnd > regionEnd))
					return null;
			}
		}
		return super.modelRange2WidgetRange(modelRange);
	}

	/**
	 * Sets the correction assistant for the viewer. This method is temporary
	 * workaround until the base adds a generic way to add
	 * quickfix/quickassist.
	 * 
	 * @param correctionAssistant
	 */
	public void setCorrectionAssistant(IContentAssistant correctionAssistant) {
		// correction assistant
		if (fCorrectionAssistant != null)
			fCorrectionAssistant.uninstall();
		fCorrectionAssistant = correctionAssistant;
		if (fCorrectionAssistant != null) {
			// configuration
			if (fCorrectionAssistant instanceof ContentAssistant) {
				((ContentAssistant) fCorrectionAssistant).setDocumentPartitioning(getDocumentPartitioning());
			}

			fCorrectionAssistant.install(this);
			fCorrectionAssistantInstalled = true;
		}
		else {
			// disable the correction assist operation if no correction
			// assistant
			enableOperation(QUICK_FIX, false);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.jface.text.source.ISourceViewer#setDocument(org.eclipse.jface.text.IDocument,
	 *      org.eclipse.jface.text.source.IAnnotationModel, int, int)
	 */
	public void setDocument(IDocument document, IAnnotationModel annotationModel, int modelRangeOffset, int modelRangeLength) {
		// partial fix for:
		// https://w3.opensource.ibm.com/bugzilla/show_bug.cgi?id=1970
		// when our document is set, especially to null during close,
		// immediately uninstall the reconciler.
		// this is to avoid an unnecessary final "reconcile"
		// that blocks display thread
		if (document == null) {
			if (fReconciler != null) {
				fReconciler.uninstall();
			}
		}

		super.setDocument(document, annotationModel, modelRangeOffset, modelRangeLength);

		if (document instanceof IStructuredDocument) {
			IStructuredDocument structuredDocument = (IStructuredDocument) document;

			// notify highlighter
			updateHighlighter(structuredDocument);

			// set document in the viewer-based undo manager
			if (fUndoManager != null) {
				fUndoManager.disconnect();
				fUndoManager.connect(this);
			}
			// CaretEvent is not sent to ViewerSelectionManager after Save As.
			// Need to notify ViewerSelectionManager here.
			// notifyViewerSelectionManager(getSelectedRange().x,
			// getSelectedRange().y);
		}
	}

	/**
	 * Use the active editor to set a status line message
	 * 
	 * @param msg
	 */
	private void setErrorMessage(String msg) {
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		if (window != null) {
			IWorkbenchPage page = window.getActivePage();
			if (page != null) {
				IEditorPart editor = page.getActiveEditor();
				if (editor != null) {
					IEditorStatusLine statusLine = (IEditorStatusLine) editor.getAdapter(IEditorStatusLine.class);
					if (statusLine != null)
						statusLine.setMessage(true, msg, null);
				}
			}
		}
	}

	/**
	 * Uninstalls anything that was installed by configure
	 */
	public void unconfigure() {
		Logger.trace("Source Editor", "StructuredTextViewer::unconfigure entry"); //$NON-NLS-1$ //$NON-NLS-2$
		if (fHighlighter != null) {
			fHighlighter.uninstall();
			fHighlighter = null;
		}
		if (fCorrectionAssistant != null) {
			fCorrectionAssistant.uninstall();
		}

		if (fAnnotationHover instanceof StructuredTextAnnotationHover) {
			((StructuredTextAnnotationHover) fAnnotationHover).release();
		}

		if (fOverviewRulerAnnotationHover instanceof StructuredTextAnnotationHover) {
			((StructuredTextAnnotationHover) fOverviewRulerAnnotationHover).release();
		}

		// doesn't seem to be handled elsewhere, so we'll be sure error
		// messages's are cleared.
		setErrorMessage(null);

		super.unconfigure();
		fConfiguration = null;
		Logger.trace("Source Editor", "StructuredTextViewer::unconfigure exit"); //$NON-NLS-1$ //$NON-NLS-2$
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.wst.sse.core.undo.IDocumentSelectionMediator#undoOperationSelectionChanged(org.eclipse.wst.sse.core.undo.UndoDocumentEvent)
	 */
	public void undoOperationSelectionChanged(UndoDocumentEvent event) {
		if (event.getRequester() != null && event.getRequester().equals(this) && event.getDocument().equals(getDocument())) {
			// BUG107687: Undo/redo do not scroll editor
			ITextSelection selection = new TextSelection(event.getOffset(), event.getLength());
			setSelection(selection, true);
		}
	}

	private void updateHighlighter(IStructuredDocument document) {
		// if highlighter has not been created yet, initialize and install it
		if (fHighlighter == null && fConfiguration instanceof StructuredTextViewerConfiguration) {
			String[] types = fConfiguration.getConfiguredContentTypes(this);
			for (int i = 0; i < types.length; i++) {
				String t = types[i];

				// add highlighter/linestyleprovider
				LineStyleProvider[] providers = ((StructuredTextViewerConfiguration) fConfiguration).getLineStyleProviders(this, t);
				if (providers != null) {
					for (int j = 0; j < providers.length; ++j) {
						// delay creation of highlighter till
						// linestyleprovider needs to be added
						// do not create highlighter if no valid document
						if (fHighlighter == null)
							fHighlighter = new Highlighter();
						fHighlighter.addProvider(t, providers[j]);
					}
				}
			}

			// initialize highlighter after linestyleproviders were added
			if (fHighlighter != null) {
				fHighlighter.setDocumentPartitioning(fConfiguration.getConfiguredDocumentPartitioning(this));
				fHighlighter.install(this);
			}
		}
		if (fHighlighter != null)
			fHighlighter.setDocument(document);
	}

	/**
	 * Make sure indentation is correct before using.
	 */
	private void updateIndentationPrefixes() {
		SourceViewerConfiguration configuration = fConfiguration;
		if (fConfiguration != null) {
			String[] types = configuration.getConfiguredContentTypes(this);
			for (int i = 0; i < types.length; i++) {
				String[] prefixes = configuration.getIndentPrefixes(this, types[i]);
				if (prefixes != null && prefixes.length > 0)
					setIndentPrefixes(prefixes, types[i]);
			}
		}
	}
}
