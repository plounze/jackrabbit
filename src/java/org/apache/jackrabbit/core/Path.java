/*
 * Copyright 2004-2005 The Apache Software Foundation or its licensors,
 *                     as applicable.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.core;

import org.apache.jackrabbit.core.util.Text;
import org.apache.xml.utils.XMLChar;

import javax.jcr.NamespaceException;
import javax.jcr.PathNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The <code>Path</code> utility class provides misc. methods to resolve and
 * nornalize JCR-style item paths. <br>
 * Each path consistnes of path elements and is immutable. it has the following
 * properties:<br>
 * <code>absolute()</code>:<br>
 * A path is absolute, if the first path element denotes the root element '/'.
 * <p/>
 * <code>isRelative()</code>:<br>
 * A path is relative, if the first path element does not denote the root element.
 * I.e. is always the opposite of <code>absolute</code>.
 * <p/>
 * <code>normalized()</code>:<br>
 * A path is normalized, if all '.' and '..' path elements are resolved as much
 * as possible. If the path is absolute, it is normalized if it contains
 * no such elements. for example the path '../../a' is normalized where as
 * '../../b/../a/.' is not. Normalized path never have '.' elements.
 * absolte normalilzed paths have no and relative normalized paths have no or
 * only leading '..' elements.<br>
 * <p/>
 * <code>isCanonical()</code>:<br>
 * A path is canonical, if its absolute and normalized.
 * <p/>
 * <p/>
 * the external string representation of a path has the following format:
 * <p/>
 * <xmp>
 * path ::= properpath ['/']
 * properpath ::= abspath | relpath
 * abspath ::= '/' relpath
 * relpath ::= [relpath '/'] pathelement
 * pathelement ::= name ['[' number ']']
 * number ::= << An integer > 0 >>
 * <p/>
 * name ::= [prefix ':'] simplename
 * prefix ::= << Any valid XML Name >>
 * simplename ::= nonspacestring [[string] nonspacestring]
 * string ::= [string] char
 * char ::= nonspace | space
 * nonspacestring ::= [nonspacestring] nonspace
 * space ::= << ' ' (the space character) >>
 * nonspace ::= << Any Unicode character except
 * '/', ':', '[', ']', '*',
 * '''(the single quote),
 * '"'(the double quote),
 * any whitespace character >>
 * </xmp>
 */
public final class Path {

    /**
     * the 'root' element. i.e. '/'
     */
    private static final PathElement ROOT_ELEMENT = new RootElement();

    /**
     * the 'current' element. i.e. '.'
     */
    private static final PathElement CURRENT_ELEMENT = new CurrentElement();

    /**
     * the 'parent' element. i.e. '..'
     */
    private static final PathElement PARENT_ELEMENT = new ParentElement();

    /**
     * the root path
     */
    public static final Path ROOT = new Path(new PathElement[]{ROOT_ELEMENT}, true);

    /**
     * Pattern used to validate and parse path elements:<p>
     * <ul>
     * <li>group 1 is .
     * <li>group 2 is ..
     * <li>group 3 is namespace prefix incl. delimiter (colon)
     * <li>group 4 is namespace prefix excl. delimiter (colon)
     * <li>group 5 is localName
     * <li>group 6 is index incl. brackets
     * <li>group 7 is index excl. brackets
     * </ul>
     */
    private static final Pattern PATH_ELEMENT_PATTERN = Pattern.compile("(\\.)|(\\.\\.)|(([^ /:\\[\\]*'\"|](?:[^/:\\[\\]*'\"|]*[^ /:\\[\\]*'\"|])?):)?([^ /:\\[\\]*'\"|](?:[^/:\\[\\]*'\"|]*[^ /:\\[\\]*'\"|])?)(\\[([1-9]\\d*)\\])?");

    /**
     * the elements of this path
     */
    private final PathElement[] elements;

    /**
     * flag indicating if this path is normalized
     */
    private final boolean normalized;

    /**
     * flag indicating if this path is absolute
     */
    private final boolean absolute;

    /**
     * the cached hashcode of this path
     */
    private int hash = 0;

    /**
     * the cached 'toString' of this path
     */
    private String string;

    /**
     * Private constructor
     *
     * @param elements
     * @param isNormalized
     */
    private Path(PathElement[] elements, boolean isNormalized) {
        if (elements == null || elements.length == 0) {
            throw new IllegalArgumentException("Empty paths are not allowed");
        }
        this.elements = elements;
        this.absolute = elements[0].denotesRoot();
        this.normalized = isNormalized;
    }

    //------------------------------------------------------< factory methods >
    /**
     * Creates a new <code>Path</code> from the given <code>jcrPath</code>
     * string. If <code>normalize</code> is <code>true</code>, the returned
     * path will be normalized (or canonicalized if absolute).
     *
     * @param jcrPath
     * @param resolver
     * @param normalize
     * @return
     * @throws MalformedPathException
     */
    public static Path create(String jcrPath, NamespaceResolver resolver,
                              boolean normalize)
            throws MalformedPathException {
        return normalize
                ? parse(jcrPath, null, resolver).getNormalizedPath()
                : parse(jcrPath, null, resolver);
    }

    /**
     * Creates a new <code>Path</code> out of the given <code>parent</code> path
     * and a relative path string. If <code>canonicalize</code> is
     * <code>true</code>, the returned path will be canonicalized.
     *
     * @param parent
     * @param relJCRPath
     * @param resolver
     * @param canonicalize
     * @return
     * @throws MalformedPathException
     */
    public static Path create(Path parent, String relJCRPath,
                              NamespaceResolver resolver, boolean canonicalize)
            throws MalformedPathException {
        return canonicalize
                ? parse(relJCRPath, parent, resolver).getCanonicalPath()
                : parse(relJCRPath, parent, resolver);
    }

    /**
     * Creates a new <code>Path</code> out of the given <code>parent<code> path
     * string and the given relative path string. If <code>normalize</code> is
     * <code>true</code>, the returned path will be normalized (or
     * canonicalized, if the parent path is absolute).
     *
     * @param parent
     * @param relPath
     * @param normalize
     * @return
     * @throws MalformedPathException
     */
    public static Path create(Path parent, Path relPath, boolean normalize)
            throws MalformedPathException {
        if (relPath.isAbsolute()) {
            throw new MalformedPathException("relPath is not a relative path");
        }

        PathBuilder pb = new PathBuilder(parent.getElements());
        pb.addAll(relPath.getElements());

        return normalize
                ? pb.getPath().getNormalizedPath()
                : pb.getPath();
    }

    /**
     * Creates a new <code>Path</code> out of the given <code>parent<code> path
     * string and the give name. If <code>normalize</code> is <code>true</code>,
     * the returned path will be normalized (or canonicalized, if the parent
     * path is absolute).
     *
     * @param parent
     * @param name
     * @param normalize
     * @return
     * @throws MalformedPathException
     */
    public static Path create(Path parent, QName name, boolean normalize)
            throws MalformedPathException {
        PathBuilder pb = new PathBuilder(parent.getElements());
        pb.addLast(name);

        return normalize
                ? pb.getPath().getNormalizedPath()
                : pb.getPath();
    }

    /**
     * Creates a new <code>Path</code> out of the given <code>parent<code> path
     * string and the give name and index. If <code>normalize</code> is
     * <code>true</code>, the returned path will be normalized
     * (or canonicalized, if the parent path is absolute).
     *
     * @param parent
     * @param name
     * @param index
     * @param normalize
     * @return
     * @throws MalformedPathException
     */
    public static Path create(Path parent, QName name, int index, boolean normalize)
            throws MalformedPathException {
        PathBuilder pb = new PathBuilder(parent.getElements());
        pb.addLast(name, index);

        return normalize
                ? pb.getPath().getNormalizedPath()
                : pb.getPath();
    }

    /**
     * Creates a relative path based on a {@link QName} and an index.
     *
     * @param name  single {@link QName} for this relative path.
     * @param index index of the sinlge name element.
     * @return the relative path created from <code>name</code>.
     * @throws IllegalArgumentException if <code>index</code> is negative.
     */
    public static Path create(QName name, int index) {
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative: " + index);
        }
        PathElement elem;
        if (index < 1) {
            elem = new PathElement(name);
        } else {
            elem = new PathElement(name, index);
        }
        return new Path(new PathElement[]{elem}, !elem.equals(CURRENT_ELEMENT));
    }

    //-------------------------------------------------------< implementation >
    /**
     * Parses the give string an d returns an array of path elements. if
     * <code>master</code> is not <code>null</code>, it is prepended to the
     * returned list. If <code>resolver</code> is <code>null</code>, this
     * method only checks the format of the string and returns <code>null</code>.
     *
     * @param jcrPath
     * @param master
     * @param resolver
     * @return
     * @throws MalformedPathException
     */
    private static Path parse(String jcrPath, Path master, NamespaceResolver resolver)
            throws MalformedPathException {
        // shortcut
        if ("/".equals(jcrPath)) {
            return ROOT;
        }

        // split path into path elements
        String[] elems = Text.explode(jcrPath, '/', true);
        if (elems.length == 0) {
            throw new MalformedPathException("empty path");
        }

        ArrayList list = new ArrayList();
        boolean isNormalized = true;
        boolean leadingParent = true;
        if (master != null) {
            isNormalized = master.normalized;
            // a master path was specified; the 'path' argument is assumed
            // to be a relative path
            for (int i = 0; i < master.elements.length; i++) {
                list.add(master.elements[i]);
                leadingParent &= master.elements[i].denotesParent();
            }
        }

        for (int i = 0; i < elems.length; i++) {
            // validate & parse path element
            String prefix;
            String localName;
            int index;

            String elem = elems[i];
            if (i == 0 && elem.length() == 0) {
                // path is absolute, i.e. the first element is the root element
                if (!list.isEmpty()) {
                    throw new MalformedPathException("'" + jcrPath + "' is not a relative path");
                }
                list.add(ROOT_ELEMENT);
                leadingParent = false;
                continue;
            }
            Matcher matcher = PATH_ELEMENT_PATTERN.matcher(elem);
            if (matcher.matches()) {
                if (resolver == null) {
                    // check only
                    continue;
                }

                if (matcher.group(1) != null) {
                    // group 1 is .
                    list.add(CURRENT_ELEMENT);
                    leadingParent = false;
                    isNormalized = false;
                } else if (matcher.group(2) != null) {
                    // group 2 is ..
                    list.add(PARENT_ELEMENT);
                    isNormalized &= leadingParent;
                } else {
                    // element is a name

                    // check for prefix (group 3)
                    if (matcher.group(3) != null) {
                        // prefix specified
                        // group 4 is namespace prefix excl. delimiter (colon)
                        prefix = matcher.group(4);
                        // check if the prefix is a valid XML prefix
                        if (!XMLChar.isValidNCName(prefix)) {
                            // illegal syntax for prefix
                            throw new MalformedPathException("'" + jcrPath + "' is not a valid path: '" + elem + "' specifies an illegal namespace prefix");
                        }
                    } else {
                        // no prefix specified
                        prefix = "";
                    }

                    // group 5 is localName
                    localName = matcher.group(5);

                    // check for index (group 6)
                    if (matcher.group(6) != null) {
                        // index specified
                        // group 7 is index excl. brackets
                        index = Integer.parseInt(matcher.group(7));
                    } else {
                        // no index specified
                        index = 0;
                    }

                    String nsURI;
                    try {
                        nsURI = resolver.getURI(prefix);
                    } catch (NamespaceException nse) {
                        // unknown prefix
                        throw new MalformedPathException("'" + jcrPath + "' is not a valid path: '" + elem + "' specifies an unmapped namespace prefix");
                    }

                    PathElement element;
                    if (index == 0) {
                        element = new PathElement(nsURI, localName);
                    } else {
                        element = new PathElement(nsURI, localName, index);
                    }
                    list.add(element);
                    leadingParent = false;
                }
            } else {
                // illegal syntax for path element
                throw new MalformedPathException("'" + jcrPath + "' is not a valid path: '" + elem + "' is not a legal path element");
            }
        }
        return resolver == null
                ? null
                : new Path((PathElement[]) list.toArray(new PathElement[list.size()]), isNormalized);
    }

    //------------------------------------------------------< utility methods >
    /**
     * Checks if <code>jcrPath</code> is a valid JCR-style absolute or relative
     * path.
     *
     * @param jcrPath the path to be checked
     * @throws MalformedPathException If <code>jcrPath</code> is not a valid
     *                                JCR-style path.
     */
    public static void checkFormat(String jcrPath) throws MalformedPathException {
        parse(jcrPath, null, null);
    }

    //-------------------------------------------------------< public methods >
    /**
     * Tests whether this path represents the root path, i.e. "/".
     *
     * @return true if this path represents the root path; false otherwise.
     */
    public boolean denotesRoot() {
        return absolute && elements.length == 1;
    }

    /**
     * Tests whether this path is absolute, i.e. whether it starts with "/".
     *
     * @return true if this path is absolute; false otherwise.
     */
    public boolean isAbsolute() {
        return absolute;
    }

    /**
     * Tests whether this path is canonical, i.e. whether it is absolute and
     * does not contain redundant elements such as "." and "..".
     *
     * @return true if this path is canonical; false otherwise.
     * @see #isAbsolute()
     */
    public boolean isCanonical() {
        return absolute && normalized;
    }

    /**
     * Tests whether this path is normalized, i.e. whether it does not
     * contain redundant elements such as "." and "..".
     * <p/>
     * Note that a normalized path can still contain ".." elements if they are
     * not redundant, e.g. "../../a/b/c" would be a normalized relative path,
     * whereas "../a/../../a/b/c" wouldn't (although they're semantically
     * equivalent).
     *
     * @return true if this path is normalized; false otherwise.
     * @see #getNormalizedPath()
     */
    public boolean isNormalized() {
        return normalized;
    }

    /**
     * Returns the normalized path representation of this path. This typically
     * involves removing/resolving redundant elements such as "." and ".." from
     * the path, e.g. "/a/./b/.." will be normalized to "/a", "../../a/b/c/.."
     * will be normalized to "../../a/b", and so on.
     * <p/>
     * If the normalized path results in an empty path (eg: 'a/..') or if an
     * absolute path is normalized that would result in a 'negative' path
     * (eg: /a/../../) a MalformedPathException is thrown.
     *
     * @return a normalized path representation of this path
     * @throws MalformedPathException if the path cannot be normalized.
     * @see #isNormalized()
     */
    public Path getNormalizedPath() throws MalformedPathException {
        if (isNormalized()) {
            return this;
        }
        LinkedList queue = new LinkedList();
        PathElement last = null;
        for (int i = 0; i < elements.length; i++) {
            PathElement elem = elements[i];
            if (elem.denotesCurrent()) {
                continue;
            } else if (elem.denotesParent() && last != null && !last.denotesParent()) {
                if (last.denotesRoot()) {
                    // the first element is the root element;
                    // ".." would refer to the parent of root
                    throw new MalformedPathException("Path can not be canonicalized: unresolvable '..' element");
                }
                queue.removeLast();
                last = queue.isEmpty() ? null : (PathElement) queue.getLast();
            } else {
                queue.add(last = elem);
            }
        }
        if (queue.isEmpty()) {
            throw new MalformedPathException("Path can not be normalized: would result in an empty path.");
        }
        return new Path((PathElement[]) queue.toArray(new PathElement[queue.size()]), true);
    }

    /**
     * Returns the canonical path representation of this path. This typically
     * involves removing/resolving redundant elements such as "." and ".." from
     * the path.
     *
     * @return a canonical path representation of this path
     * @throws MalformedPathException if this path can not be canonicalized
     *                                (e.g. if it is relative)
     */
    public Path getCanonicalPath() throws MalformedPathException {
        if (isCanonical()) {
            return this;
        }
        if (!isAbsolute()) {
            throw new MalformedPathException("only an absolute path can be canonicalized.");
        }
        return getNormalizedPath();
    }

    /**
     * Computes the relative path from <code>this</code> absolute path to
     * <code>other</code>.
     *
     * @param other an absolute path
     * @return the relative path from <code>this</code> path to
     *         <code>other</code> path
     * @throws MalformedPathException if either <code>this</code> or
     *                                <code>other</code> path is not absolute
     */
    public Path computeRelativePath(Path other) throws MalformedPathException {
        if (other == null) {
            throw new IllegalArgumentException("null argument");
        }

        // make sure both paths are absolute
        if (!isAbsolute() || !other.isAbsolute()) {
            throw new MalformedPathException("not an absolute path");
        }

        // make sure we're comparing canonical paths
        Path p0 = getCanonicalPath();
        Path p1 = other.getCanonicalPath();

        if (p0.equals(p1)) {
            // both paths are equal, the relative path is therefore '.'
            PathBuilder pb = new PathBuilder();
            pb.addLast(CURRENT_ELEMENT);
            return pb.getPath();
        }

        // determine length of common path fragment
        int lengthCommon = 0;
        for (int i = 0; i < p0.elements.length && i < p1.elements.length; i++) {
            if (!p0.elements[i].equals(p1.elements[i])) {
                break;
            }
            lengthCommon++;
        }

        PathBuilder pb = new PathBuilder();
        if (lengthCommon < p0.elements.length) {
            /**
             * the common path fragment is an ancestor of this path;
             * this has to be accounted for by prepending '..' elements
             * to the relative path
             */
            int tmp = p0.elements.length - lengthCommon;
            while (tmp-- > 0) {
                pb.addFirst(PARENT_ELEMENT);
            }
        }
        // add remainder of other path
        for (int i = lengthCommon; i < p1.elements.length; i++) {
            pb.addLast(p1.elements[i]);
        }
        // we're done
        return pb.getPath();
    }

    /**
     * Returns the ancestor path of the specified relative degree.
     * <p/>
     * An ancestor of relative degree <i>x</i> is the path that is <i>x</i>
     * levels up along the path.
     * <ul>
     * <li><i>degree</i> = 0 returns this path.
     * <li><i>degree</i> = 1 returns the parent of this path.
     * <li><i>degree</i> = 2 returns the grandparent of this path.
     * <li>And so on to <i>degree</i> = <i>n</i>, where <i>n</i> is the depth
     * of this path, which returns the root path.
     * </ul>
     * <p/>
     * Note that there migth be an unexpected result if <i>this</i> path is not
     * normalized, e.g. the ancestor of degree = 1 of the path "../.." would
     * be ".." although this is not the parent of "../..".
     *
     * @param degree the relative degree of the requested ancestor.
     * @return the ancestor path of the specified degree.
     * @throws PathNotFoundException    if there is no ancestor of the specified
     *                                  degree
     * @throws IllegalArgumentException if <code>degree</code> is negative
     */
    public Path getAncestor(int degree) throws PathNotFoundException {
        if (degree < 0) {
            throw new IllegalArgumentException("degree must be >= 0");
        } else if (degree == 0) {
            return this;
        }
        int length = elements.length - degree;
        if (length < 1) {
            throw new PathNotFoundException("no such ancestor path of degree " + degree);
        }
        PathElement[] elements = new PathElement[length];
        for (int i = 0; i < length; i++) {
            elements[i] = this.elements[i];
        }
        return new Path(elements, normalized);
    }

    /**
     * Returns the number of ancestors of this path. This is the equivalent
     * of <code>{@link #getDepth()} - 1</code>.
     * <p/>
     * Note that the returned value might be negative if this path is not
     * canonical, e.g. the depth of "../../a" is -1, its ancestor count is
     * therefore -2.
     *
     * @return the number of ancestors of this path
     * @see #getDepth()
     * @see #getLength()
     * @see #isCanonical()
     */
    public int getAncestorCount() {
        return getDepth() - 1;
    }

    /**
     * Returns the length of this path, i.e. the number of its elements.
     * Note that the root element "/" counts as a separate element, e.g.
     * the length of "/a/b/c" is 4 whereas the length of "a/b/c" is 3.
     * <p/>
     * Also note that the special elements "." and ".." are not treated
     * specially, e.g. both "/a/./.." and "/a/b/c" have a length of 4
     * but this value does not necessarily reflect the true hierarchy level as
     * returned by <code>{@link #getDepth()}</code>.
     *
     * @return the length of this path
     * @see #getDepth()
     * @see #getAncestorCount()
     */
    public int getLength() {
        return elements.length;
    }

    /**
     * Returns the depth of this path. The depth reflects the absolute or
     * relative hierarchy level this path is representing, depending on whether
     * this path is an absolute or a relative path. The depth also takes '.'
     * and '..' elements into account.
     * <p/>
     * Note that the returned value might be negative if this path is not
     * canonical, e.g. the depth of "../../a" is -1.
     *
     * @return the depth this path
     * @see #getLength()
     * @see #getAncestorCount()
     */
    public int getDepth() {
        int depth = 0;
        for (int i = 0; i < elements.length; i++) {
            if (elements[i].denotesParent()) {
                depth--;
            } else if (!elements[i].denotesCurrent()) {
                depth++;
            }
        }
        return depth;
    }

    /**
     * Determines if <i>this</i> path is an ancestor of the specified path,
     * based on their (absolute or relative) hierarchy level as returned by
     * <code>{@link #getDepth()}</code>.
     *
     * @return <code>true</code> if <code>other</code> is a descendant;
     *         otherwise <code>false</code>
     * @throws MalformedPathException if not both paths are either absolute or
     *                                relative.
     * @see #getDepth()
     */
    public boolean isAncestorOf(Path other) throws MalformedPathException {
        if (other == null) {
            throw new IllegalArgumentException("null argument");
        }
        // make sure both paths are either absolute or relative
        if (isAbsolute() != other.isAbsolute()) {
            throw new MalformedPathException("cannot compare a relative path with an absolute path");
        }
        // make sure we're comparing normalized paths
        Path p0 = getNormalizedPath();
        Path p1 = other.getNormalizedPath();

        if (p0.equals(p1)) {
            return false;
        }
        // calculate depth of paths (might be negative)
        if (p0.getDepth() >= p1.getDepth()) {
            return false;
        }
        for (int i = 0; i < p0.elements.length; i++) {
            if (!p0.elements[i].equals(p1.elements[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Determines if <i>this</i> path is a descendant of the specified path,
     * based on their (absolute or relative) hierarchy level as returned by
     * <code>{@link #getDepth()}</code>.
     *
     * @return <code>true</code> if <code>other</code> is an ancestor;
     *         otherwise <code>false</code>
     * @throws MalformedPathException if not both paths are either absolute or
     *                                relative.
     * @see #getDepth()
     */
    public boolean isDescendantOf(Path other) throws MalformedPathException {
        if (other == null) {
            throw new IllegalArgumentException("null argument");
        }
        return other.isAncestorOf(this);
    }

    /**
     * Returns the name element (i.e. the last element) of this path.
     *
     * @return the name element of this path
     */
    public PathElement getNameElement() {
        return elements[elements.length - 1];
    }

    /**
     * Returns the elements of this path.
     *
     * @return the elements of this path.
     */
    public PathElement[] getElements() {
        return elements;
    }

    /**
     * @param resolver
     * @return
     * @throws NoPrefixDeclaredException
     */
    public String toJCRPath(NamespaceResolver resolver) throws NoPrefixDeclaredException {
        if (denotesRoot()) {
            // shortcut
            return "/";
        }
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < elements.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            PathElement element = elements[i];
            // name
            sb.append(element.toJCRName(resolver));
        }
        return sb.toString();
    }

    /**
     * Returns the internal string representation of this <code>Path</code>.
     * <p/>
     * Note that the returned string is not a valid JCR path, i.e. the
     * namespace URI's of the individual path elements are not replaced with
     * their mapped prefixes. Call
     * <code>{@link #toJCRPath(NamespaceResolver)}</code>
     * for a JCR path representation.
     *
     * @return the internal string representation of this <code>Path</code>.
     */
    public String toString() {
        // Path is immutable, we can store the string representation
        if (string == null) {
            StringBuffer sb = new StringBuffer();
            for (int i = 0; i < elements.length; i++) {
                if (i > 0) {
                    // @todo find safe path separator char that does not conflict with chars in serialized QName
                    sb.append('\t');
                }
                PathElement element = elements[i];
                String elem = element.toString();
                sb.append(elem);
            }
            string = sb.toString();
        }
        return string;
    }

    /**
     * Returns a <code>Path</code> holding the value of the specified
     * string. The string must be in the format returned by the
     * <code>Path.toString()</code> method.
     *
     * @param s a <code>String</code> containing the <code>Path</code>
     *          representation to be parsed.
     * @return the <code>Path</code> represented by the argument
     * @throws IllegalArgumentException if the specified string can not be parsed
     *                                  as a <code>Path</code>.
     * @see #toString()
     */
    public static Path valueOf(String s) {
        if ("".equals(s) || s == null) {
            throw new IllegalArgumentException("invalid Path literal");
        }

        // split into path elements

        // @todo find safe path separator char that does not conflict with chars in serialized QName
        String[] elements = Text.explode(s, '\t', true);
        ArrayList list = new ArrayList();
        boolean isNormalized = true;
        boolean leadingParent = true;
        for (int i = 0; i < elements.length; i++) {
            PathElement elem = PathElement.fromString(elements[i]);
            list.add(elem);
            leadingParent &= elem.denotesParent();
            isNormalized &= !elem.denotesCurrent() && (leadingParent || !elem.denotesParent());
        }

        return new Path((PathElement[]) list.toArray(new PathElement[list.size()]), isNormalized);
    }

    /**
     * Returns a hash code value for this path.
     *
     * @return a hash code value for this path.
     * @see java.lang.Object#hashCode()
     */
    public int hashCode() {
        // Path is immutable, we can store the computed hash code value
        int h = hash;
        if (h == 0) {
            h = 17;
            for (int i = 0; i < elements.length; i++) {
                h = 37 * h + elements[i].hashCode();
            }
            hash = h;
        }
        return h;
    }

    /**
     * Compares the specified object with this path for equality.
     *
     * @param obj the object to be compared for equality with this path.
     * @return <tt>true</tt> if the specified object is equal to this path.
     */
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof Path) {
            Path other = (Path) obj;
            return Arrays.equals(elements, other.elements);
        }
        return false;
    }

    //--------------------------------------------------------< inner classes >
    /**
     * package protected inner class used to build a path from path elements;
     * this class does not validate the format of the path elements!
     */
    static final class PathBuilder implements Cloneable {

        /**
         * the list of path elements of the constructed path
         */
        private final LinkedList queue;

        /**
         * flag indicating if the current path is normalized
         */
        boolean isNormalized = true;

        /**
         * flag indicating if the current path has leading parent '..' elements
         */
        boolean leadingParent = true;

        /**
         * Creates a new PathBuilder.
         */
        PathBuilder() {
            queue = new LinkedList();
        }

        /**
         * Creates a new PathBuilder and initialized it with the given path
         * elements.
         *
         * @param elements
         */
        PathBuilder(PathElement[] elements) {
            this();
            addAll(elements);
        }

        /**
         * Adds the {@link Path#ROOT_ELEMENT}.
         */
        void addRoot() {
            addFirst(ROOT_ELEMENT);
        }

        /**
         * Adds the given elemenets
         *
         * @param elements
         */
        void addAll(PathElement[] elements) {
            for (int i = 0; i < elements.length; i++) {
                addLast(elements[i]);
            }
        }

        /**
         * Inserts the element at the beginning of the path to be built.
         *
         * @param elem
         */
        public void addFirst(PathElement elem) {
            if (queue.isEmpty()) {
                isNormalized &= !elem.denotesCurrent();
                leadingParent = elem.denotesParent();
            } else {
                isNormalized &= !elem.denotesCurrent() && (!leadingParent || elem.denotesParent());
                leadingParent |= elem.denotesParent();
            }
            queue.addFirst(elem);
        }

        /**
         * Inserts the element at the beginning of the path to be built.
         *
         * @param name
         */
        void addFirst(QName name) {
            addFirst(new PathElement(name));
        }

        /**
         * Inserts the element at the beginning of the path to be built.
         *
         * @param name
         * @param index
         */
        void addFirst(QName name, int index) {
            addFirst(new PathElement(name, index));
        }

        /**
         * Inserts the element at the end of the path to be built.
         *
         * @param elem
         */
        public void addLast(PathElement elem) {
            queue.addLast(elem);
            leadingParent &= elem.denotesParent();
            isNormalized &= !elem.denotesCurrent() && (leadingParent || !elem.denotesParent());
        }

        /**
         * Inserts the element at the end of the path to be built.
         *
         * @param name
         */
        void addLast(QName name) {
            addLast(new PathElement(name));
        }

        /**
         * Inserts the element at the end of the path to be built.
         *
         * @param name
         * @param index
         */
        void addLast(QName name, int index) {
            addLast(new PathElement(name, index));
        }

        /**
         * Assembles the built path and returns a new {@link Path}.
         *
         * @return
         * @throws MalformedPathException if the internal path element queue is empty.
         */
        Path getPath() throws MalformedPathException {
            PathElement[] elements = (PathElement[]) queue.toArray(new PathElement[queue.size()]);
            // validate path
            if (elements.length == 0) {
                throw new MalformedPathException("empty path");
            }

            // no need to check the path format, assuming all names correct
            return new Path(elements, isNormalized);
        }

        public Object clone() {
            PathBuilder clone = new PathBuilder();
            clone.queue.addAll(queue);
            return clone;
        }
    }

    public static class RootElement extends PathElement {
        // use a literal that is an illegal name character to avoid collisions
        static final String LITERAL = "*";

        private RootElement() {
            super(Constants.NS_DEFAULT_URI, "");
        }

        // PathElement override
        public boolean denotesRoot() {
            return true;
        }

        // PathElement override
        public boolean denotesCurrent() {
            return false;
        }

        // PathElement override
        public boolean denotesParent() {
            return false;
        }

        // PathElement override
        public boolean denotesName() {
            return false;
        }

        // PathElement override
        public String toJCRName(NamespaceResolver resolver) throws NoPrefixDeclaredException {
            return "";
        }

        // Object override
        public String toString() {
            return LITERAL;
        }
    }

    public static class CurrentElement extends PathElement {
        static final String LITERAL = ".";

        private CurrentElement() {
            super(Constants.NS_DEFAULT_URI, LITERAL);
        }

        // PathElement override
        public boolean denotesRoot() {
            return false;
        }

        // PathElement override
        public boolean denotesCurrent() {
            return true;
        }

        // PathElement override
        public boolean denotesParent() {
            return false;
        }

        // PathElement override
        public boolean denotesName() {
            return false;
        }

        // PathElement override
        public String toJCRName(NamespaceResolver resolver) throws NoPrefixDeclaredException {
            return LITERAL;
        }

        // Object override
        public String toString() {
            return LITERAL;
        }
    }

    public static class ParentElement extends PathElement {
        static final String LITERAL = "..";

        private ParentElement() {
            super(Constants.NS_DEFAULT_URI, LITERAL);
        }

        // PathElement override
        public boolean denotesRoot() {
            return false;
        }

        // PathElement override
        public boolean denotesCurrent() {
            return false;
        }

        // PathElement override
        public boolean denotesParent() {
            return true;
        }

        // PathElement override
        public boolean denotesName() {
            return false;
        }

        // PathElement override
        public String toJCRName(NamespaceResolver resolver) throws NoPrefixDeclaredException {
            return LITERAL;
        }

        // Object override
        public String toString() {
            return LITERAL;
        }
    }

    public static class PathElement {

        private final QName name;

        /**
         * 1-based index; 0 if not explicitly specified (which is equivalent to
         * specifying 1)
         */
        private final int index;

        private PathElement(String namespaceURI, String localName) {
            this(new QName(namespaceURI, localName));
        }

        private PathElement(String namespaceURI, String localName, int index) {
            this(new QName(namespaceURI, localName), index);
        }

        private PathElement(QName name) {
            if (name == null) {
                throw new IllegalArgumentException("name must not be null");
            }
            this.name = name;
            this.index = 0;
        }

        private PathElement(QName name, int index) {
            if (name == null) {
                throw new IllegalArgumentException("name must not be null");
            }
            if (index < 1) {
                throw new IllegalArgumentException("index is 1-based");
            }
            this.index = index;
            this.name = name;
        }

        /**
         * Returns the name of this path element.
         *
         * @return the name
         */
        public QName getName() {
            return name;
        }

        /**
         * Returns the 1-based index or 0 if no index was specified (which is
         * equivalent to specifying 1).
         *
         * @return Returns the 1-based index or 0 if no index was specified.
         */
        public int getIndex() {
            return index;
        }

        /**
         * Returns <code>true</code> if this element denotes the <i>root</i> element,
         * otherwise returns <code>false</code>.
         *
         * @return <code>true</code> if this element denotes the <i>root</i>
         *         element; otherwise <code>false</code>
         */
        public boolean denotesRoot() {
            return equals(ROOT_ELEMENT);
        }

        /**
         * Returns <code>true</code> if this element denotes the <i>parent</i>
         * ('..') element, otherwise returns <code>false</code>.
         *
         * @return <code>true</code> if this element denotes the <i>parent</i>
         *         element; otherwise <code>false</code>
         */
        public boolean denotesParent() {
            return equals(PARENT_ELEMENT);
        }

        /**
         * Returns <code>true</code> if this element denotes the <i>current</i>
         * ('.') element, otherwise returns <code>false</code>.
         *
         * @return <code>true</code> if this element denotes the <i>current</i>
         *         element; otherwise <code>false</code>
         */
        public boolean denotesCurrent() {
            return equals(CURRENT_ELEMENT);
        }

        /**
         * Returns <code>true</code> if this element represents a regular name
         * (i.e. neither root, '.' nor '..'), otherwise returns <code>false</code>.
         *
         * @return <code>true</code> if this element represents a regular name;
         *         otherwise <code>false</code>
         */
        public boolean denotesName() {
            return !denotesRoot() && !denotesParent() && !denotesCurrent();
        }

        public String toJCRName(NamespaceResolver resolver) throws NoPrefixDeclaredException {
            StringBuffer sb = new StringBuffer();
            // name
            sb.append(name.toJCRName(resolver));
            // index
            int index = getIndex();
            /**
             * FIXME the [1] subscript should only be suppressed if the item
             * in question can't have same-name siblings.
             */
            //if (index > 0) {
            if (index > 1) {
                sb.append('[');
                sb.append(index);
                sb.append(']');
            }
            return sb.toString();
        }

        public String toString() {
            StringBuffer sb = new StringBuffer();
            // name
            sb.append(name.toString());
            // index
            int index = getIndex();
            if (index > 0) {
                sb.append('[');
                sb.append(index);
                sb.append(']');
            }
            return sb.toString();
        }

        public static PathElement fromString(String s) {
            if (s == null) {
                throw new IllegalArgumentException("null PathElement literal");
            }
            if (s.equals(RootElement.LITERAL)) {
                return ROOT_ELEMENT;
            } else if (s.equals(CurrentElement.LITERAL)) {
                return CURRENT_ELEMENT;
            } else if (s.equals(ParentElement.LITERAL)) {
                return PARENT_ELEMENT;
            }

            int pos = s.indexOf('[');
            if (pos == -1) {
                QName name = QName.valueOf(s);
                return new PathElement(name.getNamespaceURI(), name.getLocalName());
            }
            QName name = QName.valueOf(s.substring(0, pos));
            int pos1 = s.indexOf(']');
            if (pos1 == -1) {
                throw new IllegalArgumentException("invalid PathElement literal: " + s + " (missing ']')");
            }
            try {
                int index = Integer.valueOf(s.substring(pos + 1, pos1)).intValue();
                if (index < 1) {
                    throw new IllegalArgumentException("invalid PathElement literal: " + s + " (index is 1-based)");
                }
                return new PathElement(name.getNamespaceURI(), name.getLocalName(), index);
            } catch (Throwable t) {
                throw new IllegalArgumentException("invalid PathElement literal: " + s + " (" + t.getMessage() + ")");
            }
        }

        public int hashCode() {
            // @todo treat index==0 as index==1?
            int h = 17;
            h = 37 * h + index;
            h = 37 * h + name.hashCode();
            return h;
        }

        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj instanceof PathElement) {
                PathElement other = (PathElement) obj;
                return name.equals(other.name)
                        // @todo treat index==0 as index==1?
                        && index == other.index;
            }
            return false;
        }
    }
}
