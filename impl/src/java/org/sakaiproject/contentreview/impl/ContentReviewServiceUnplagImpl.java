package org.sakaiproject.contentreview.impl;

import com.unplag.Unplag;
import com.unplag.model.UCheck;
import com.unplag.model.UFile;
import com.unplag.model.UType;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
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

import java.io.InputStream;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
public class ContentReviewServiceUnplagImpl implements ContentReviewService {

    @Setter
    private ServerConfigurationService serverConfigurationService;
    @Setter
    private UnplagItemDao unplagItemDao;

    private Unplag unplag;
    private ExecutorService pool;
    private UType uType;

    public void init() {
        String key = serverConfigurationService.getString("unplag.key", null);
        String secret = serverConfigurationService.getString("unplag.secret", null);
        unplag = new Unplag(key, secret);

        int threadsCount = serverConfigurationService.getInt("unplag.pool.size", 4);
        pool = Executors.newFixedThreadPool(threadsCount);

        int checkType = serverConfigurationService.getInt("unplag.check.type", 1); // default WEB
        uType = UType.values()[checkType];
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
                //upload
                UFile uFile;
                try (InputStream is = resource.streamContent()) {
                    String fileName = resource.getProperties().getProperty(ResourceProperties.PROP_DISPLAY_NAME);
                    uFile = unplag.uploadFile(is, FilenameUtils.getExtension(id), FilenameUtils.getBaseName(fileName));

                    // check
                    UCheck uCheck = unplag
                            .createCheck(uFile.getId(), uType, null, null, null);
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
        return getUnplagItemForReport(contentId).getLink();
    }

    @Override
    public String getReviewReportStudent(String contentId, String assignmentRef, String userId)
            throws QueueException, ReportException {
        return getUnplagItemForReport(contentId).getLink();
    }

    @Override
    public String getReviewReportInstructor(String contentId, String assignmentRef, String userId)
            throws QueueException, ReportException {
        return getUnplagItemForReport(contentId).getEditLink();
    }

    @Override
    public Long getReviewStatus(String contentId) throws QueueException {
        return null;
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
        return "Unplag";
    }

    @Override
    public void resetUserDetailsLockedItems(String userId) {
    }

    @Override
    public boolean allowAllContent() {
        return true;
    }

    @Override
    public boolean isAcceptableContent(ContentResource resource) {
        return true;
    }

    @Override
    public Map<String, SortedSet<String>> getAcceptableExtensionsToMimeTypes() {
        return null;
    }

    @Override
    public Map<String, SortedSet<String>> getAcceptableFileTypesToExtensions() {
        return null;
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

    private UnplagItem getUnplagItemForReport(String contentId) throws ReportException {
        UnplagItem item = unplagItemDao.getByContentId(contentId);
        if (item.getError() != null)
            throw new ReportException(item.getError());
        else
            return item;
    }
}
