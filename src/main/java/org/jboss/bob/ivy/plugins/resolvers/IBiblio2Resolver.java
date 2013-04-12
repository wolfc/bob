/*
 * JBoss, Home of Professional Open Source.
 * Copyright (c) 2013, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.bob.ivy.plugins.resolvers;

import org.apache.ivy.core.IvyPatternHelper;
import org.apache.ivy.core.module.descriptor.Artifact;
import org.apache.ivy.core.module.id.ModuleRevisionId;
import org.apache.ivy.plugins.repository.Repository;
import org.apache.ivy.plugins.repository.Resource;
import org.apache.ivy.plugins.resolver.IBiblioResolver;
import org.apache.ivy.plugins.resolver.util.ResolvedResource;
import org.apache.ivy.util.ContextualSAXHandler;
import org.apache.ivy.util.Message;
import org.apache.ivy.util.XMLHelper;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * @author <a href="mailto:cdewolf@redhat.com">Carlo de Wolf</a>
 */
public class IBiblio2Resolver extends IBiblioResolver {
    private static final String M2_PER_MODULE_PATTERN
            = "[revision]/[artifact]-[revision](-[classifier]).[ext]";
    private static final String M2_PATTERN  = "[organisation]/[module]/" + M2_PER_MODULE_PATTERN;

    private String findSnapshotVersion(ModuleRevisionId mrid) {
        if (!isM2compatible()) {
            return null;
        }

        if (shouldUseMavenMetadata(getWholePattern())) {
            InputStream metadataStream = null;
            try {
                String metadataLocation = IvyPatternHelper.substitute(
                        getRoot() + "[organisation]/[module]/[revision]/maven-metadata.xml", mrid);
                Resource metadata = getRepository().getResource(metadataLocation);
                if (metadata.exists()) {
                    metadataStream = metadata.openStream();
                    final StringBuffer timestamp = new StringBuffer();
                    final StringBuffer buildNumer = new StringBuffer();
                    XMLHelper.parse(metadataStream, null, new ContextualSAXHandler() {
                        public void endElement(String uri, String localName, String qName)
                                throws SAXException {
                            if ("metadata/versioning/snapshot/timestamp".equals(getContext())) {
                                timestamp.append(getText());
                            }
                            if ("metadata/versioning/snapshot/buildNumber"
                                    .equals(getContext())) {
                                buildNumer.append(getText());
                            }
                            super.endElement(uri, localName, qName);
                        }
                    }, null);
                    if (timestamp.length() > 0) {
                        // we have found a timestamp, so this is a snapshot unique version
                        String rev = mrid.getRevision();
                        rev = rev.substring(0, rev.length() - "SNAPSHOT".length());
                        rev = rev + timestamp.toString() + "-" + buildNumer.toString();

                        return rev;
                    }
                } else {
                    Message.verbose("\tmaven-metadata not available: " + metadata);
                }
            } catch (IOException e) {
                Message.verbose(
                        "impossible to access maven metadata file, ignored: " + e.getMessage());
            } catch (SAXException e) {
                Message.verbose(
                        "impossible to parse maven metadata file, ignored: " + e.getMessage());
            } catch (ParserConfigurationException e) {
                Message.verbose(
                        "impossible to parse maven metadata file, ignored: " + e.getMessage());
            } finally {
                if (metadataStream != null) {
                    try {
                        metadataStream.close();
                    } catch (IOException e) {
                        // ignored
                    }
                }
            }
        }
        return null;
    }

    private String getWholePattern() {
        return getRoot() + getPattern();
    }

    private List listRevisionsWithMavenMetadata(Repository repository, Map tokenValues) {
        String metadataLocation = IvyPatternHelper.substituteTokens(
                getRoot() + "[organisation]/[module]/maven-metadata.xml", tokenValues);
        return listRevisionsWithMavenMetadata(repository, metadataLocation);
    }

    private List listRevisionsWithMavenMetadata(Repository repository, String metadataLocation) {
        List revs = null;
        InputStream metadataStream = null;
        try {
            Resource metadata = repository.getResource(metadataLocation);
            if (metadata.exists()) {
                Message.verbose("\tlisting revisions from maven-metadata: " + metadata);
                final List metadataRevs = new ArrayList();
                metadataStream = metadata.openStream();
                XMLHelper.parse(metadataStream, null, new ContextualSAXHandler() {
                    public void endElement(String uri, String localName, String qName)
                            throws SAXException {
                        if ("metadata/versioning/versions/version".equals(getContext())) {
                            metadataRevs.add(getText().trim());
                        }
                        super.endElement(uri, localName, qName);
                    }
                }, null);
                revs = metadataRevs;
            } else {
                Message.verbose("\tmaven-metadata not available: " + metadata);
            }
        } catch (IOException e) {
            Message.verbose(
                    "impossible to access maven metadata file, ignored: " + e.getMessage());
        } catch (SAXException e) {
            Message.verbose(
                    "impossible to parse maven metadata file, ignored: " + e.getMessage());
        } catch (ParserConfigurationException e) {
            Message.verbose(
                    "impossible to parse maven metadata file, ignored: " + e.getMessage());
        } finally {
            if (metadataStream != null) {
                try {
                    metadataStream.close();
                } catch (IOException e) {
                    // ignored
                }
            }
        }
        return revs;
    }

    @Override
    protected ResolvedResource[] listResources(final Repository repository, final ModuleRevisionId mrid, final String pattern, final Artifact artifact) {
        if (shouldUseMavenMetadata(pattern)) {
            List revs = listRevisionsWithMavenMetadata(
                    repository, mrid.getModuleId().getAttributes());
            if (revs != null) {
                Message.debug("\tfound revs: " + revs);
                List rres = new ArrayList();
                for (Iterator iter = revs.iterator(); iter.hasNext();) {
                    String rev = (String) iter.next();
                    ModuleRevisionId historicalMrid = ModuleRevisionId.newInstance(mrid, rev);

                    String patternForRev = pattern;
                    if (rev.endsWith("SNAPSHOT")) {
                        String snapshotVersion = findSnapshotVersion(historicalMrid);
                        if (snapshotVersion != null) {
                            patternForRev = pattern.replaceFirst("\\-\\[revision\\]", "-" + snapshotVersion);
                        }
                    }
                    String resolvedPattern = IvyPatternHelper.substitute(
                            patternForRev, historicalMrid, artifact);
                    try {
                        Resource res = repository.getResource(resolvedPattern);
                        if ((res != null) && res.exists()) {
                            rres.add(new ResolvedResource(res, rev));
                        }
                    } catch (IOException e) {
                        Message.warn(
                                "impossible to get resource from name listed by maven-metadata.xml:"
                                        + rres + ": " + e.getMessage());
                    }
                }
                return (ResolvedResource[]) rres.toArray(new ResolvedResource[rres.size()]);
            } else {
                // maven metadata not available or something went wrong,
                // use default listing capability
                return super.listResources(repository, mrid, pattern, artifact);
            }
        } else {
            return super.listResources(repository, mrid, pattern, artifact);
        }
    }

    private boolean shouldUseMavenMetadata(String pattern) {
        return isUseMavenMetadata() && isM2compatible() && pattern.endsWith(M2_PATTERN);
    }

}
