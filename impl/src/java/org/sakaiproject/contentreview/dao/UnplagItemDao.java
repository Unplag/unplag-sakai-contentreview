package org.sakaiproject.contentreview.dao;

import org.sakaiproject.contentreview.model.UnplagItem;

public interface UnplagItemDao {

	void saveUnplagItem(UnplagItem item);
	UnplagItem getByContentId(String contentId);

}
