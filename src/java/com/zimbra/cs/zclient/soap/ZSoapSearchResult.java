/*
 * ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1
 * 
 * The contents of this file are subject to the Mozilla Public License
 * Version 1.1 ("License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://www.zimbra.com/license
 * 
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See
 * the License for the specific language governing rights and limitations
 * under the License.
 * 
 * The Original Code is: Zimbra Collaboration Suite Server.
 * 
 * The Initial Developer of the Original Code is Zimbra, Inc.
 * Portions created by Zimbra are Copyright (C) 2006 Zimbra, Inc.
 * All Rights Reserved.
 * 
 * Contributor(s): 
 * 
 * ***** END LICENSE BLOCK *****
 */
package com.zimbra.cs.zclient.soap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.zimbra.cs.service.ServiceException;
import com.zimbra.cs.service.mail.MailService;
import com.zimbra.cs.zclient.ZSearchHit;
import com.zimbra.cs.zclient.ZSearchResult;
import com.zimbra.cs.zclient.soap.ZSoapSB;
import com.zimbra.soap.Element;

class ZSoapSearchResult implements ZSearchResult {
 
    private List<ZSearchHit> mHits;
    private boolean mHasMore;
    private String mSortBy;
    private int mOffset;

    public ZSoapSearchResult(Element e) throws ServiceException {
        mSortBy = e.getAttribute(MailService.A_SORTBY);
        mHasMore = e.getAttributeBool(MailService.A_QUERY_MORE);
        mOffset = (int) e.getAttributeLong(MailService.A_QUERY_OFFSET);
        mHits = new ArrayList<ZSearchHit>();
        Map<String,ZSoapEmailAddress> cache = new HashMap<String, ZSoapEmailAddress>();
        for (Element h: e.listElements()) {
            if (h.getName().equals(MailService.E_CONV)) {
                mHits.add(new ZSoapConversationHit(h, cache));
            } else if (e.getName().equals(MailService.E_MSG)) {
                mHits.add(new ZSoapMessageHit(h, cache));
            }
        }
    }

    /**
     * @return ZSearchHit objects from search
     */
    public List<ZSearchHit> getHits() {
        return mHits;
    }
    
    /**
     * @return true if there are more search results on the server
     */
    public boolean hasMore() {
        return mHasMore;
    }
    
    /**
     * @return the sort by value
     */
    public String getSortBy() {
        return mSortBy;
    }
    
    /**
     * @return offset of the search
     */
    public int getOffset() {
        return mOffset;
    }

    public String toString() {
        ZSoapSB sb = new ZSoapSB();
        sb.beginStruct("ZSearchResult");
        sb.add("more", mHasMore);
        sb.add("sortBy", mSortBy);
        sb.add("offset", mOffset);
        sb.add("hits", mHits, false);
        sb.endStruct();
        return sb.toString();
    }
    
}
