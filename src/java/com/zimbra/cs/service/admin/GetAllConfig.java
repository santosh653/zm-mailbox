/*
 * Created on Jun 17, 2004
 */
package com.zimbra.cs.service.admin;

import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import com.zimbra.cs.account.Provisioning;
import com.zimbra.cs.service.Element;
import com.zimbra.cs.service.ServiceException;
import com.zimbra.soap.ZimbraContext;

/**
 * @author schemers
 */
public class GetAllConfig extends AdminDocumentHandler {
    
	public Element handle(Element request, Map context) throws ServiceException {

        ZimbraContext lc = getZimbraContext(context);
	    Provisioning prov = Provisioning.getInstance();

	    Map attrs = prov.getConfig().getAttrs();

	    Element response = lc.createElement(AdminService.GET_ALL_CONFIG_RESPONSE);
	    
        for (Iterator mit = attrs.entrySet().iterator(); mit.hasNext(); ) {
            Map.Entry entry = (Entry) mit.next();
            String name = (String) entry.getKey();
            Object value = entry.getValue();
            if (value instanceof String[]) {
                String sv[] = (String[]) value;
                GetConfig.doConfig(response, name, sv);
            } else if (value instanceof String){
                GetConfig.doConfig(response, name, (String) value);
            }
        }
	    return response;
	}

}
