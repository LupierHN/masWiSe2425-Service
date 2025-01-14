package de.htw_berlin.fb4.mas.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class Location {
    private List<Id> ids;
    private String keyword;
    private String keywordId;
    private String type;
    private boolean leanLocker;
    private boolean dfLocker;
}