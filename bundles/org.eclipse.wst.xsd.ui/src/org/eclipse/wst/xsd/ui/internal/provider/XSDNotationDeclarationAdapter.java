/*******************************************************************************
 * Copyright (c) 2001, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.wst.xsd.ui.internal.provider;

import org.eclipse.emf.common.notify.AdapterFactory;
import org.eclipse.swt.graphics.Image;
import org.eclipse.wst.xsd.ui.internal.XSDEditorPlugin;
import org.eclipse.xsd.XSDNotationDeclaration;


public class XSDNotationDeclarationAdapter extends XSDAbstractAdapter
{

  /**
   * @param adapterFactory
   */
  public XSDNotationDeclarationAdapter(AdapterFactory adapterFactory)
  {
    super(adapterFactory);
  }
  
  public Image getImage(Object object)
  {
    // return XSDEditorPlugin.getPlugin().getIconImage("full/obj16/XSDNotationDeclaration");
    return XSDEditorPlugin.getXSDImage("icons/XSDNotation.gif");
  }

  public String getText(Object object)
  {
    XSDNotationDeclaration xsdNotationDeclaration = ((XSDNotationDeclaration)object);
    String result = xsdNotationDeclaration.getName();
    return result == null ? "" : result;
  }


}
