package org.sakaiproject.contentreview.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

@NoArgsConstructor
@Getter
@Setter
@ToString
public class UnplagItem implements Serializable {

    private Long id;
    private String contentId;
    private String siteId;
    private String assignmentRef;
    private String userId;
    private String link;
    private String editLink;
    private Integer score;
    private String error;

}
