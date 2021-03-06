
package info.freelibrary.djatoka.view;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Properties;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import nu.xom.Builder;
import nu.xom.Document;
import nu.xom.Element;
import nu.xom.ParsingException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import gov.lanl.adore.djatoka.util.IOUtils;

import info.freelibrary.djatoka.Constants;
import info.freelibrary.util.RegexDirFilter;
import info.freelibrary.util.RegexFileFilter;
import info.freelibrary.util.StringUtils;

public class ViewServlet extends HttpServlet implements Constants {

    /**
     * The <code>serialVersionUID</code>
     */
    private static final long serialVersionUID = 5298506582675331814L;

    private static final String XSL_STYLESHEET = "<?xml-stylesheet href='/view.xsl' type='text/xsl'?>";

    private static final Logger LOGGER = LoggerFactory.getLogger(ViewServlet.class);

    private Properties myProps;

    @Override
    protected void doGet(final HttpServletRequest aRequest, final HttpServletResponse aResponse)
            throws ServletException, IOException {
        final File tifDir = new File(myProps.getProperty(TIFF_DATA_DIR));
        final File jp2Dir = new File(myProps.getProperty(JP2_DATA_DIR));
        final String servletPath = aRequest.getServletPath();
        HttpSession session = aRequest.getSession();
        String dirParam = aRequest.getPathInfo();

        if (dirParam == null) { // easier to config redirect here than in Jetty
            final String path = "/" + aRequest.getContextPath();
            aResponse.sendRedirect(!path.equals("/") ? path : "" + "/view/");
            return;
        }

        if (!jp2Dir.exists()) {
            aResponse.sendError(HttpServletResponse.SC_NOT_FOUND, "The JP2 directory cannot be found: " +
                    jp2Dir.getAbsolutePath());
        }

        final File dir = new File(jp2Dir, dirParam);

        if (!session.isNew()) {
            final String size = (String) session.getAttribute(JP2_SIZE_ATTR);

            if (size == null || size.equals("null")) {
                session.invalidate();
                session = aRequest.getSession();
            }
        }

        if (session.isNew()) {
            final File mdFile = new File(jp2Dir, "djatoka.xml");

            if (mdFile.exists() && mdFile.length() > 0) {
                String jp2Size, tifSize, jp2Count, tifCount;

                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Reading directory stats from: {}", mdFile.getAbsolutePath());
                }

                try {
                    final Document doc = new Builder().build(mdFile);
                    final Element jp2s = (Element) doc.query("//jp2s").get(0);
                    final Element tifs = (Element) doc.query("//tifs").get(0);

                    tifSize = tifs.getAttribute(TIF_SIZE_ATTR).getValue();
                    tifCount = tifs.getAttribute(TIF_COUNT_ATTR).getValue();
                    jp2Size = jp2s.getAttribute(JP2_SIZE_ATTR).getValue();
                    jp2Count = jp2s.getAttribute(JP2_COUNT_ATTR).getValue();
                } catch (final ParsingException details) {
                    jp2Size = null;
                    tifSize = null;
                    jp2Count = null;
                    tifCount = null;
                }

                session.setAttribute(JP2_SIZE_ATTR, jp2Size);
                session.setAttribute(TIF_SIZE_ATTR, tifSize);
                session.setAttribute(JP2_COUNT_ATTR, jp2Count);
                session.setAttribute(TIF_COUNT_ATTR, tifCount);
            } else {
                final StatsCompilation stats = new StatsCompilation(tifDir, jp2Dir);

                session.setAttribute(JP2_SIZE_ATTR, stats.getJP2sSize());
                session.setAttribute(TIF_SIZE_ATTR, stats.getTIFsSize());
                session.setAttribute(JP2_COUNT_ATTR, stats.getJP2sCount());
                session.setAttribute(TIF_COUNT_ATTR, stats.getTIFsCount());

                stats.save(mdFile);
            }
        }

        FilenameFilter dirFilter;
        FilenameFilter jp2Filter;
        PrintWriter writer;

        // We need the ending slash for the browser to construct links
        if (dirParam == null || !dirParam.endsWith("/")) {
            aResponse.sendRedirect(servletPath + "/");
            return;
        }

        if (!dirParam.startsWith("/")) {
            dirParam = "/" + dirParam;
        }

        // Catch folks who want something significant from altering URL
        if (dirParam.equalsIgnoreCase("/thumbnails/")) {
            aResponse.sendRedirect(servletPath + "/");
            return;
        }

        try {
            if (!jp2Dir.exists()) {
                aResponse.sendError(HttpServletResponse.SC_NOT_FOUND, StringUtils.format(
                        "Configured JP2 directory ({}) doesn't exist", jp2Dir.getAbsolutePath()));
            } else {
                final String[] atts =
                        new String[] { (String) session.getAttribute(TIF_COUNT_ATTR),
                            (String) session.getAttribute(TIF_SIZE_ATTR),
                            (String) session.getAttribute(JP2_COUNT_ATTR),
                            (String) session.getAttribute(JP2_SIZE_ATTR) };

                if (!tifDir.exists() && LOGGER.isWarnEnabled()) {
                    LOGGER.warn("The configured TIFF directory ({}) doesn't exist", tifDir);
                }

                writer = getWriter(aResponse);
                writer.write(XSL_STYLESHEET);

                // Being sketchy and not using a real XML library like I should
                writer.write("<djatokaViewer>");
                writer.write(StringUtils.format("<tifStats fileCount='{}' totalSize='{}'/>"
                        + "<jp2Stats fileCount='{}' totalSize='{}'/>", atts));

                writer.write("<defaultPath>" + servletPath + "</defaultPath>");
                writer.write("<path>" + tokenize(dirParam) + "</path>");

                if (dir.exists()) {
                    String name;

                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Viewing contents of {}", dir);
                    }

                    dirFilter = new RegexDirFilter(".*");
                    jp2Filter = new RegexFileFilter(JP2_FILE_PATTERN);

                    for (final File file : dir.listFiles(dirFilter)) {
                        name = encodeEntities(file.getName());
                        writer.write("<dir name='" + name + "'/>");
                    }

                    for (final File file : dir.listFiles(jp2Filter)) {
                        name = encodeEntities(file.getName());
                        writer.write("<file name='" + name + "'/>");
                    }
                }

                writer.write("</djatokaViewer>");

            }
        } catch (final Exception details) {
            LOGGER.error(details.getMessage(), details);
            aResponse.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, details.getMessage());
        }
    }

    @Override
    public void init() throws ServletException {
        final String dir = getServletContext().getRealPath("/WEB-INF/classes") + "/";
        final String propertiesFile = dir + PROPERTIES_FILE;

        try {
            myProps = IOUtils.loadConfigByPath(propertiesFile);

            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Loaded properties file: {}", propertiesFile);
            }
        } catch (final Exception details) {
            throw new ServletException(details);
        }
    }

    @Override
    public void log(final String aMessage, final Throwable aThrowable) {
        super.log(aMessage, aThrowable);
    }

    @Override
    public void log(final String aMessage) {
        super.log(aMessage);
    }

    private String encodeEntities(final String aName) {
        String name = aName.replace("&", "&amp;"); // no prior entity encoding
        name = name.replace("\"", "&quot;").replace("%", "&#37;");
        name = name.replace("<", "&lt;").replace(">", "&gt;");
        return name.replace("'", "&apos;");
    }

    private PrintWriter getWriter(final HttpServletResponse aResponse) throws IOException {
        aResponse.setContentType("application/xml");
        return aResponse.getWriter();
    }

    private String tokenize(final String aPath) {
        final StringBuilder builder = new StringBuilder();
        final String[] pathParts = aPath.split("/");

        for (final String pathPart : pathParts) {
            if (!pathPart.equals("")) {
                builder.append("<part>").append(pathPart).append("</part>");
            }
        }

        return builder.toString();
    }
}
