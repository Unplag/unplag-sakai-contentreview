package org.sakaiproject.contentreview.impl;

import com.unplag.Unplag;
import com.unplag.model.UCheck;
import com.unplag.model.UFile;
import com.unplag.model.UType;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.glassfish.jersey.uri.internal.JerseyUriBuilder;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.content.api.ContentResource;
import org.sakaiproject.contentreview.dao.UnplagItemDao;
import org.sakaiproject.contentreview.exception.QueueException;
import org.sakaiproject.contentreview.exception.ReportException;
import org.sakaiproject.contentreview.exception.SubmissionException;
import org.sakaiproject.contentreview.exception.TransientSubmissionException;
import org.sakaiproject.contentreview.model.ContentReviewItem;
import org.sakaiproject.contentreview.model.UnplagItem;
import org.sakaiproject.contentreview.service.ContentReviewService;
import org.sakaiproject.entity.api.ResourceProperties;
import org.sakaiproject.site.api.Site;
import org.apache.commons.io.FilenameUtils;
import org.sakaiproject.user.api.PreferencesService;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class ContentReviewServiceUnplagImpl implements ContentReviewService {

    private static final Map<String, SortedSet<String>> acceptFilesMap = new HashMap<>();
    private static final Map<String, SortedSet<String>> acceptFileTypesMap = new HashMap<>();

    static {
        acceptFilesMap.put(".docx", new TreeSet<>(Arrays.asList(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/zip"
        )));
        acceptFilesMap.put(".odt", new TreeSet<>(Arrays.asList(
                "application/vnd.oasis.opendocument.text"
        )));
        acceptFilesMap.put(".doc", new TreeSet<>(Arrays.asList(
                "application/msword"
        )));
        acceptFilesMap.put(".pdf", new TreeSet<>(Arrays.asList(
                "application/pdf"
        )));
        acceptFilesMap.put(".rtf", new TreeSet<>(Arrays.asList(
                "application/rtf",
                "text/rtf"
        )));
        acceptFilesMap.put(".txt", new TreeSet<>(Arrays.asList(
                "text/plain",
                "application/txt",
                "text/anytext",
                "application/octet-stream"
        )));
        acceptFilesMap.put(".html", new TreeSet<>(Arrays.asList(
                "text/html",
                "application/xhtml+xml",
                "text/plain"
        )));
        acceptFilesMap.put(".pages", new TreeSet<>(Arrays.asList(
                "application/x-iwork-pages-sffpages"
        )));

        acceptFileTypesMap.put("Word", new TreeSet<>(Arrays.asList(".doc", ".docx")));
        acceptFileTypesMap.put("PDF", new TreeSet<>(Arrays.asList(".pdf")));
        acceptFileTypesMap.put("OpenOffice", new TreeSet<>(Arrays.asList(".odt")));
        acceptFileTypesMap.put("Apple Pages", new TreeSet<>(Arrays.asList(".pages")));
        acceptFileTypesMap.put("RTF", new TreeSet<>(Arrays.asList(".rtf")));
        acceptFileTypesMap.put("Text", new TreeSet<>(Arrays.asList(".txt", ".html")));
    }

    @Setter
    private PreferencesService preferencesService;
    @Setter
    private ServerConfigurationService serverConfigurationService;
    @Setter
    private UnplagItemDao unplagItemDao;

    private Unplag unplag;
    private ExecutorService pool;
    private UType uType;
    private int maxFileSize;
    private boolean allowAnyFileType;
    private boolean excludeCitations;
    private boolean excludeReferences;

    private static final String SERVICE_NAME = "Unplag";

    public void init() {
        String key = serverConfigurationService.getString("unplag.key", null);
        String secret = serverConfigurationService.getString("unplag.secret", null);
        unplag = new Unplag(key, secret);

        int threadsCount = serverConfigurationService.getInt("unplag.poolSize", 4);
        pool = Executors.newFixedThreadPool(threadsCount);

        int checkType = serverConfigurationService.getInt("unplag.checkType", 1); // default WEB
        uType = UType.values()[checkType];

        maxFileSize = serverConfigurationService.getInt("unplag.maxFileSize", 20971520); //default 20MB
        allowAnyFileType = serverConfigurationService.getBoolean("unplag.allowAnyFileType", false);
        excludeCitations = serverConfigurationService.getBoolean("unplag.exclude.citations", true);
        excludeReferences = serverConfigurationService.getBoolean("unplag.exclude.references", true);
    }

    public void destroy() {
        if (pool != null) {
            pool.shutdown();
        }
    }

    @Override
    public void queueContent(String userId, String siteId, String assignmentReference, List<ContentResource> content)
            throws QueueException {

        for (final ContentResource resource : content) {

            String id = resource.getId();
            UnplagItem item = new UnplagItem();
            item.setContentId(id);
            item.setUserId(userId);
            item.setSiteId(siteId);
            item.setAssignmentRef(assignmentReference);
            unplagItemDao.saveUnplagItem(item);

            CompletableFuture.runAsync(() -> {

                log.info("Processing resource " + id);
                if (!checkContentResource(resource)) {
                    //ignore
                    item.setError("Unsupported file");
                    unplagItemDao.saveUnplagItem(item);
                    return;
                }

                //upload
                UFile uFile;
                try (InputStream is = resource.streamContent()) {
                    uFile = unplag.uploadFile(
                            is,
                            getResourceExtension(resource),
                            FilenameUtils.getBaseName(getResourceFileName(resource))
                    );

                    // check
                    UCheck uCheck = unplag
                            .createCheck(uFile.getId(), uType, null, excludeCitations, excludeReferences);
                    long uCheckId = uCheck.getId();
                    uCheck = unplag.waitForCheckInfo(uCheckId);
                    item.setScore(Math.round(100f - uCheck.getReport().getSimilarity()));
                    item.setLink(uCheck.getReport().getViewUrl());
                    item.setEditLink(uCheck.getReport().getViewEditUrl());
                } catch (Exception e) {
                    String message = e.getMessage();
                    log.error(message, e);
                    item.setError(message);
                }

                unplagItemDao.saveUnplagItem(item);
            }, pool)

                    .thenRun(() -> log.info(String.format("%s is completed", item.getContentId())));
        }
    }

    @Override
    public int getReviewScore(String contentId, String assignmentRef, String userId)
            throws Exception {

        UnplagItem item = unplagItemDao.getByContentId(contentId);
        if (item != null) {
            if (item.getLink() != null)
                return item.getScore();
            else
                return -1; // in progress
        }
        throw new ReportException("UnplagItem with id " + contentId + " doesn't exist");
    }

    @Override
    public String getReviewReport(String contentId, String assignmentRef, String userId)
            throws QueueException, ReportException {
        return getReportLink(contentId, userId, false);
    }

    @Override
    public String getReviewReportStudent(String contentId, String assignmentRef, String userId)
            throws QueueException, ReportException {
        return getReportLink(contentId, userId, false);
    }

    @Override
    public String getReviewReportInstructor(String contentId, String assignmentRef, String userId)
            throws QueueException, ReportException {
        return getReportLink(contentId, userId, true);
    }

    @Override
    public Long getReviewStatus(String contentId) throws QueueException {
        UnplagItem item = unplagItemDao.getByContentId(contentId);
        if (item == null) {
            return ContentReviewItem.NOT_SUBMITTED_CODE;
        } else if (item.getLink() != null) {
            return ContentReviewItem.SUBMITTED_REPORT_AVAILABLE_CODE;
        } else if (item.getError() != null) {
            return ContentReviewItem.SUBMISSION_ERROR_RETRY_CODE;
        }
        return ContentReviewItem.SUBMITTED_AWAITING_REPORT_CODE;
    }

    @Override
    public Date getDateQueued(String contextId) throws QueueException {
        return null;
    }

    @Override
    public Date getDateSubmitted(String contextId) throws QueueException, SubmissionException {
        return null;
    }

    @Override
    public void processQueue() {
    }

    @Override
    public void checkForReports() {
    }

    @Override
    public List<ContentReviewItem> getReportList(String siteId, String taskId)
            throws QueueException, SubmissionException, ReportException {
        return null;
    }

    @Override
    public List<ContentReviewItem> getReportList(String siteId) throws QueueException, SubmissionException, ReportException {
        return null;
    }

    @Override
    public List<ContentReviewItem> getAllContentReviewItems(String siteId, String taskId)
            throws QueueException, SubmissionException, ReportException {
        return null;
    }

    @Override
    public String getServiceName() {
        return SERVICE_NAME;
    }

    @Override
    public void resetUserDetailsLockedItems(String userId) {
    }

    @Override
    public boolean allowAllContent() {
        return allowAnyFileType;
    }

    @Override
    public boolean isAcceptableContent(ContentResource resource) {
        return allowAnyFileType || checkContentResource(resource);
    }

    @Override
    public Map<String, SortedSet<String>> getAcceptableExtensionsToMimeTypes() {
        return acceptFilesMap;
    }

    @Override
    public Map<String, SortedSet<String>> getAcceptableFileTypesToExtensions() {
        return acceptFileTypesMap;
    }

    @Override
    public boolean isSiteAcceptable(Site site) {
        return true;
    }

    @Override
    public String getIconUrlforScore(Long score) {
        if (score > 80)
            return "/sakai-contentreview-tool-unplag/images/green.gif";
        else if (score > 40)
            return "/sakai-contentreview-tool-unplag/images/yellow.gif";
        else if (score >= 0)
            return "/sakai-contentreview-tool-unplag/images/red.gif";
        else
            return "/sakai-contentreview-tool-unplag/images/working.gif";
    }

    @Override
    public boolean allowResubmission() {
        return true;
    }

    @Override
    public void removeFromQueue(String ContentId) {
    }

    @Override
    public String getLocalizedStatusMessage(String messageCode, String userRef) {
        return null;
    }

    @Override
    public String getLocalizedStatusMessage(String messageCode) {
        return null;
    }

    @Override
    public String getReviewError(String contentId) {
        return unplagItemDao.getByContentId(contentId).getError();
    }

    @Override
    public String getLocalizedStatusMessage(String messageCode, Locale locale) {
        return null;
    }

    @Override
    public Map getAssignment(String siteId, String taskId) throws SubmissionException, TransientSubmissionException {
        return null;
    }

    @Override
    public void createAssignment(String siteId, String taskId, Map extraAsnnOpts)
            throws SubmissionException, TransientSubmissionException {
    }

    private String injectLanguageInReportLink(String userId, String linkStr) {
        if (linkStr == null) {
            return null;
        }

        try {
            Locale loc = preferencesService.getLocale(userId);
            //the user has no preference set - get the system default
            if (loc == null) {
                loc = Locale.getDefault();
            }

            JerseyUriBuilder b = new JerseyUriBuilder();
            b.uri(linkStr);
            b.replaceQueryParam("lang", loc.toString());

            return b.toString();
        } catch (Exception e) {
            log.warn("Failed to inject language", e);
        }
        return linkStr;
    }

    private String getReportLink(String contentId, String userId, boolean editable) throws ReportException {
        UnplagItem item = unplagItemDao.getByContentId(contentId);
        if (item == null) {
            return null;
        }

        if (item.getError() != null) {
            throw new ReportException(item.getError());
        }

        return injectLanguageInReportLink(userId, editable ? item.getEditLink() : item.getLink());
    }

    private String getResourceFileName(final ContentResource resource) {
        return FilenameUtils.getName(resource.getId());
    }

    private String getResourceExtension(final ContentResource resource) {
        final String ext = FilenameUtils.getExtension(resource.getId());
        return ext.isEmpty() ? null : ext;
    }

    private boolean checkContentResource(final ContentResource resource) {
        if (resource == null) {
            log.warn("checkContentResource for null resource");
            return false;
        }

        try {
            if (resource.getContentLength() == 0) {
                return false;
            }

            if (resource.getContentLength() > maxFileSize) {
                return false;
            }

            final String ext = "." + getResourceExtension(resource);
            if (!acceptFilesMap.containsKey(ext)) {
                return false;
            }
        } catch (Exception e) {
            log.warn("Failed to check content resource", e);
            return false;
        }

        return true;
    }
}
