/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.axiom.c14n.impl;

import org.apache.axiom.c14n.CanonicalizerSpi;
import org.apache.axiom.c14n.exceptions.CanonicalizationException;
import org.apache.axiom.c14n.helpers.AttrCompare;
import org.apache.axiom.c14n.omwrapper.AttrImpl;
import org.apache.axiom.c14n.omwrapper.interfaces.*;
import org.apache.axiom.c14n.utils.Constants;
import org.apache.axiom.c14n.utils.UnsyncByteArrayOutputStream;
import org.apache.axiom.om.OMAbstractFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.*;

public abstract class CanonicalizerBase extends CanonicalizerSpi {
    //Constants to be outputed, In char array form, so
    //less garbage is generate when outputed.
    private static final byte[] _END_PI = {'?', '>'};
    private static final byte[] _BEGIN_PI = {'<', '?'};
    private static final byte[] _END_COMM = {'-', '-', '>'};
    private static final byte[] _BEGIN_COMM = {'<', '!', '-', '-'};
    private static final byte[] __XA_ = {'&', '#', 'x', 'A', ';'};
    private static final byte[] __X9_ = {'&', '#', 'x', '9', ';'};
    private static final byte[] _QUOT_ = {'&', 'q', 'u', 'o', 't', ';'};
    private static final byte[] __XD_ = {'&', '#', 'x', 'D', ';'};
    private static final byte[] _GT_ = {'&', 'g', 't', ';'};
    private static final byte[] _LT_ = {'&', 'l', 't', ';'};
    private static final byte[] _END_TAG = {'<', '/'};
    private static final byte[] _AMP_ = {'&', 'a', 'm', 'p', ';'};
    final static AttrCompare COMPARE = new AttrCompare();
    final static String XML = "xml";
    final static String XMLNS = "xmlns";
    final static byte[] equalsStr = {'=', '\"'};
    static final int NODE_BEFORE_DOCUMENT_ELEMENT = -1;
    static final int NODE_NOT_BEFORE_OR_AFTER_DOCUMENT_ELEMENT = 0;
    static final int NODE_AFTER_DOCUMENT_ELEMENT = 1;
    //The null xmlns definiton.
    protected static final Attr nullNode;

    static {
        try {
//            nullNode = (Attr) WrapperFactory.getNode(
//                    OMAbstractFactory.getOMFactory().createOMNamespace("", ""));
            nullNode = new AttrImpl(
                    OMAbstractFactory.getOMFactory().createOMNamespace("", ""), null, null);
        } catch (Exception e) {
            throw new RuntimeException("Unable to create nullNode"/*,*/ + e);
        }
    }

    List nodeFilter;

    boolean _includeComments;

    /**
     * The node to be skiped/excluded from the OM tree
     * in subtree canonicalizations.
     */
    Node _excludeNode = null;
    OutputStream _writer = new UnsyncByteArrayOutputStream();//null;

    /**
     * Constructor CanonicalizerBase
     *
     * @param includeComments
     */
    public CanonicalizerBase(boolean includeComments) {
        this._includeComments = includeComments;
    }

    /**
     * Method engineCanonicalizeSubTree
     *
     * @param rootNode
     * @throws CanonicalizationException
     * @inheritDoc
     */
    public byte[] engineCanonicalizeSubTree(Node rootNode)
            throws CanonicalizationException {
        return engineCanonicalizeSubTree(rootNode, (Node) null);
    }

    /**
     * @param _writer The _writer to set.
     */
    public void setWriter(OutputStream _writer) {
        this._writer = _writer;
    }

    /**
     * Canonicalizes a Subtree node.
     *
     * @param rootNode    the root of the subtree to canicalize
     * @param excludeNode a node to be excluded from the canonicalize operation
     * @return The canonicalize stream.
     * @throws CanonicalizationException
     */
    byte[] engineCanonicalizeSubTree(Node rootNode, Node excludeNode)
            throws CanonicalizationException {
        this._excludeNode = excludeNode;
        try {
            NameSpaceSymbTable ns = new NameSpaceSymbTable();
            int nodeLevel = NODE_BEFORE_DOCUMENT_ELEMENT;
            if (rootNode instanceof Element) {
                //Fills the nssymbtable with the definitions of the parent of the root subnode
                getParentNameSpaces((Element) rootNode, ns);
                nodeLevel = NODE_NOT_BEFORE_OR_AFTER_DOCUMENT_ELEMENT;
            }
            this.canonicalizeSubTree(rootNode, ns, rootNode, nodeLevel);
            this._writer.close();
            if (this._writer instanceof ByteArrayOutputStream) {
                byte []result = ((ByteArrayOutputStream) this._writer).toByteArray();
                if (reset) {
                    ((ByteArrayOutputStream) this._writer).reset();
                }
                return result;
            } else if (this._writer instanceof UnsyncByteArrayOutputStream) {
                byte []result = ((UnsyncByteArrayOutputStream) this._writer).toByteArray();
                if (reset) {
                    ((UnsyncByteArrayOutputStream) this._writer).reset();
                }
                return result;
            }
            return null;

        } catch (UnsupportedEncodingException ex) {
            throw new CanonicalizationException("empty", ex);
        } catch (IOException ex) {
            throw new CanonicalizationException("empty", ex);
        }
    }

    /**
     * Method canonicalizeSubTree, this function is a recursive one.
     *
     * @param currentNode
     * @param ns
     * @param endnode
     * @throws CanonicalizationException
     * @throws IOException
     */
    final void canonicalizeSubTree(Node currentNode, NameSpaceSymbTable ns, Node endnode,
                                   int documentLevel)
            throws CanonicalizationException, IOException {
        Node sibling = null;
        Node parentNode = null;
        final OutputStream writer = this._writer;
        final Node excludeNode = this._excludeNode;
        final boolean includeComments = this._includeComments;
        Map cache = new HashMap();
        do {
            switch (currentNode.getNodeType()) {

                case Node.DOCUMENT_TYPE_NODE :
                default :
                    break;

                case Node.ENTITY_NODE :
                case Node.NOTATION_NODE :
                case Node.ATTRIBUTE_NODE :
                    // illegal node type during traversal
                    throw new CanonicalizationException("empty");

                case Node.DOCUMENT_FRAGMENT_NODE :
                case Node.DOCUMENT_NODE :
                    ns.outputNodePush();
                    sibling = currentNode.getFirstChild();
                    break;

                case Node.COMMENT_NODE :
                    if (includeComments) {
                        outputCommentToWriter((Comment) currentNode, writer, documentLevel);
                    }
                    break;

                case Node.PROCESSING_INSTRUCTION_NODE :
                    outputPItoWriter((ProcessingInstruction) currentNode, writer, documentLevel);
                    break;

                case Node.TEXT_NODE :
// Axiom gives everything as Text
                    outputTextToWriter(currentNode.getNodeValue(), writer);
                    break;
                case Node.CDATA_SECTION_NODE :
//                    break;

                case Node.ELEMENT_NODE :
                    documentLevel = NODE_NOT_BEFORE_OR_AFTER_DOCUMENT_ELEMENT;
                    if (currentNode == excludeNode) {
                        break;
                    }
                    Element currentElement = (Element) currentNode;
                    //Add a level to the nssymbtable. So latter can be pop-back.
                    ns.outputNodePush();
                    writer.write('<');
                    String name = currentElement.getTagName();
                    UtfHelper.writeByte(name, writer, cache);

                    Iterator attrs = this.handleAttributesSubtree(currentElement, ns);
                    if (attrs != null) {
                        //we output all Attrs which are available
                        while (attrs.hasNext()) {
                            Attr attr = (Attr) attrs.next();
                            outputAttrToWriter(attr.getNodeName(), attr.getNodeValue(), writer, cache);
                        }
                    }
                    writer.write('>');
                    sibling = currentNode.getFirstChild();
                    if (sibling == null) {
                        writer.write(_END_TAG);
                        UtfHelper.writeStringToUtf8(name, writer);
                        writer.write('>');
                        //We fineshed with this level, pop to the previous definitions.
                        ns.outputNodePop();
                        if (parentNode != null) {
                            sibling = currentNode.getNextSibling();
                        }
                    } else {
                        parentNode = currentElement;
                    }
                    break;
            }
            while (sibling == null && parentNode != null) {
                writer.write(_END_TAG);
                UtfHelper.writeByte(((Element) parentNode).getTagName(), writer, cache);
                writer.write('>');
                //We fineshed with this level, pop to the previous definitions.
                ns.outputNodePop();
                if (parentNode == endnode)
                    return;
                sibling = parentNode.getNextSibling();
                parentNode = parentNode.getParentNode();
                if (!(parentNode instanceof Element)) {
                    documentLevel = NODE_AFTER_DOCUMENT_ELEMENT;
                    parentNode = null;
                }
            }
            if (sibling == null)
                return;
            currentNode = sibling;
            sibling = currentNode.getNextSibling();
        } while (true);
    }

    void handleParent(Element e, NameSpaceSymbTable ns) {
        if (!e.hasAttributes()) {
            return;
        }
        NamedNodeMap attrs = e.getAttributes();
        int attrsLength = attrs.getLength();
        for (int i = 0; i < attrsLength; i++) {
            Attr N = (Attr) attrs.item(i);
            if (Constants.NamespaceSpecNS != N.getNamespaceURI()) {
                //Not a namespace definition, ignore.
                continue;
            }

            String NName = N.getLocalName();
            String NValue = N.getNodeValue();
            if (XML.equals(NName)
                    && Constants.XML_LANG_SPACE_SpecNS.equals(NValue)) {
                //xmlns:xml="http://www.w3.org/XML/1998/namespace"
                continue;
            }
            ns.addMapping(NName, NValue, N);
        }
    }

    /**
     * Adds to ns the definitons from the parent elements of el
     *
     * @param el
     * @param ns
     */
    final void getParentNameSpaces(Element el, NameSpaceSymbTable ns) {
        List parents = new ArrayList(10);
        Node n1 = el.getParentNode();
        if (!(n1 instanceof Element)) {
            return;
        }
        //Obtain all the parents of the elemnt
        Element parent = (Element) n1;
        while (parent != null) {
            parents.add(parent);
            Node n = parent.getParentNode();
            if (!(n instanceof Element)) {
                break;
            }
            parent = (Element) n;
        }
        //Visit them in reverse order.
        ListIterator it = parents.listIterator(parents.size());
        while (it.hasPrevious()) {
            Element ele = (Element) it.previous();
            handleParent(ele, ns);
        }
        Attr nsprefix;
        if (((nsprefix = ns.getMappingWithoutRendered("xmlns")) != null)
                && "".equals(nsprefix.getValue())) {
            ns.addMappingAndRender("xmlns", "", nullNode);
        }
    }

    abstract Iterator handleAttributesSubtree(Element E, NameSpaceSymbTable ns)
            throws CanonicalizationException;

    /**
     * Outputs an Attribute to the internal Writer.
     * <p/>
     * The string value of the node is modified by replacing
     * <UL>
     * <LI>all ampersands (&) with <CODE>&amp;amp;</CODE></LI>
     * <LI>all open angle brackets (<) with <CODE>&amp;lt;</CODE></LI>
     * <LI>all quotation mark characters with <CODE>&amp;quot;</CODE></LI>
     * <LI>and the whitespace characters <CODE>#x9</CODE>, #xA, and #xD, with character
     * references. The character references are written in uppercase
     * hexadecimal with no leading zeroes (for example, <CODE>#xD</CODE> is represented
     * by the character reference <CODE>&amp;#xD;</CODE>)</LI>
     * </UL>
     *
     * @param name
     * @param value
     * @param writer
     * @throws IOException
     */
    static final void outputAttrToWriter(final String name, final String value, final OutputStream writer,
                                         final Map cache) throws IOException {
        writer.write(' ');
        UtfHelper.writeByte(name, writer, cache);
        writer.write(equalsStr);
        byte  []toWrite;
        final int length = value.length();
        int i = 0;
        while (i < length) {
            char c = value.charAt(i++);

            switch (c) {

                case '&' :
                    toWrite = _AMP_;
                    break;

                case '<' :
                    toWrite = _LT_;
                    break;

                case '"' :
                    toWrite = _QUOT_;
                    break;

                case 0x09 :    // '\t'
                    toWrite = __X9_;
                    break;

                case 0x0A :    // '\n'
                    toWrite = __XA_;
                    break;

                case 0x0D :    // '\r'
                    toWrite = __XD_;
                    break;

                default :
                    if (c < 0x80) {
                        writer.write(c);
                    } else {
                        UtfHelper.writeCharToUtf8(c, writer);
                    }
                    ;
                    continue;
            }
            writer.write(toWrite);
        }

        writer.write('\"');
    }

    /**
     * Outputs a PI to the internal Writer.
     *
     * @param currentPI
     * @param writer    where to write the things
     * @throws IOException
     */
    static final void outputPItoWriter(ProcessingInstruction currentPI, OutputStream writer, int position) throws IOException {

        if (position == NODE_AFTER_DOCUMENT_ELEMENT) {
            writer.write('\n');
        }
        writer.write(_BEGIN_PI);

        final String target = currentPI.getTarget();
        int length = target.length();

        for (int i = 0; i < length; i++) {
            char c = target.charAt(i);
            if (c == 0x0D) {
                writer.write(__XD_);
            } else {
                if (c < 0x80) {
                    writer.write(c);
                } else {
                    UtfHelper.writeCharToUtf8(c, writer);
                }
                ;
            }
        }

        final String data = currentPI.getData();

        length = data.length();

        if (length > 0) {
            writer.write(' ');

            for (int i = 0; i < length; i++) {
                char c = data.charAt(i);
                if (c == 0x0D) {
                    writer.write(__XD_);
                } else {
                    UtfHelper.writeCharToUtf8(c, writer);
                }
            }
        }

        writer.write(_END_PI);
        if (position == NODE_BEFORE_DOCUMENT_ELEMENT) {
            writer.write('\n');
        }
    }

    /**
     * Method outputCommentToWriter
     *
     * @param currentComment
     * @param writer         writer where to write the things
     * @throws IOException
     */
    static final void outputCommentToWriter(Comment currentComment, OutputStream writer, int position) throws IOException {
        if (position == NODE_AFTER_DOCUMENT_ELEMENT) {
            writer.write('\n');
        }
        writer.write(_BEGIN_COMM);

        final String data = currentComment.getData();
        final int length = data.length();

        for (int i = 0; i < length; i++) {
            char c = data.charAt(i);
            if (c == 0x0D) {
                writer.write(__XD_);
            } else {
                if (c < 0x80) {
                    writer.write(c);
                } else {
                    UtfHelper.writeCharToUtf8(c, writer);
                }
                ;
            }
        }

        writer.write(_END_COMM);
        if (position == NODE_BEFORE_DOCUMENT_ELEMENT) {
            writer.write('\n');
        }
    }

    /**
     * Outputs a Text of CDATA section to the internal Writer.
     *
     * @param text
     * @param writer writer where to write the things
     * @throws IOException
     */
    static final void outputTextToWriter(final String text, final OutputStream writer) throws IOException {
        final int length = text.length();
        byte []toWrite;
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);

            switch (c) {

                case '&' :
                    toWrite = _AMP_;
                    break;

                case '<' :
                    toWrite = _LT_;
                    break;

                case '>' :
                    toWrite = _GT_;
                    break;

                case 0xD :
                    toWrite = __XD_;
                    break;

                default :
                    if (c < 0x80) {
                        writer.write(c);
                    } else {
                        UtfHelper.writeCharToUtf8(c, writer);
                    }
                    ;
                    continue;
            }
            writer.write(toWrite);
        }
    }

}
