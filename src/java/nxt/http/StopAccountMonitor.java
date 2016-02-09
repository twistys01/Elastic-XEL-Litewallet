/******************************************************************************
 * Copyright © 2013-2016 The Nxt Core Developers.                             *
 *                                                                            *
 * See the AUTHORS.txt, DEVELOPER-AGREEMENT.txt and LICENSE.txt files at      *
 * the top-level directory of this distribution for the individual copyright  *
 * holder information and the developer policies on copyright and licensing.  *
 *                                                                            *
 * Unless otherwise agreed in a custom licensing agreement, no part of the    *
 * Nxt software, including this file, may be copied, modified, propagated,    *
 * or distributed except according to the terms contained in the LICENSE.txt  *
 * file.                                                                      *
 *                                                                            *
 * Removal or modification of this copyright notice is prohibited.            *
 *                                                                            *
 ******************************************************************************/

package nxt.http;

import nxt.Account;
import nxt.AccountMonitor;
import nxt.crypto.Crypto;

import org.json.simple.JSONObject;
import org.json.simple.JSONStreamAware;

import javax.servlet.http.HttpServletRequest;

/**
 * Stop an account monitor
 *
 * A single account monitor will be stopped when the secret phrase is specified.
 * Otherwise, the administrator password must be specified and all account monitors
 * will be stopped.
 *
 * The account monitor type and account property name must be specified when the secret
 * phrase is specified.  In addition, the holding identifier must be specified when
 * the monitor type is ASSET or CURRENCY.
 */
public class StopAccountMonitor extends APIServlet.APIRequestHandler {

    static final StopAccountMonitor instance = new StopAccountMonitor();

    private StopAccountMonitor() {
        super(new APITag[] {APITag.ACCOUNTS}, "type", "holding", "property", "secretPhrase", "adminPassword");
    }
    /**
     * Process the request
     *
     * @param   req                 Client request
     * @return                      Client response
     * @throws  NxtException        Unable to process request
     */
    @Override
    JSONStreamAware processRequest(HttpServletRequest req) throws ParameterException {
        String secretPhrase = ParameterParser.getSecretPhrase(req, false);
        JSONObject response = new JSONObject();
        if (secretPhrase != null) {
            long accountId = Account.getId(Crypto.getPublicKey(secretPhrase));
            AccountMonitor.MonitorType monitorType = ParameterParser.getMonitorType(req);
            String property = ParameterParser.getAccountProperty(req);
            long holdingId = 0;
            switch (monitorType) {
                case ASSET:
                case CURRENCY:
                    holdingId = ParameterParser.getUnsignedLong(req, "holding", true);
                    break;
            }
            boolean stopped = AccountMonitor.stopMonitor(monitorType, holdingId, property, accountId);
            response.put("stopped", stopped ? 1 : 0);
        } else {
            API.verifyPassword(req);
            int count = AccountMonitor.stopAllMonitors();
            response.put("stopped", count);
        }
        return response;
    }

    @Override
    boolean requirePost() {
        return true;
    }

    @Override
    boolean allowRequiredBlockParameters() {
        return false;
    }
}
