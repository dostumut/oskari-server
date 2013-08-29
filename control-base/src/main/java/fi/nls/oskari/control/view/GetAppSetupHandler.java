package fi.nls.oskari.control.view;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import fi.nls.oskari.annotation.OskariActionRoute;
import fi.nls.oskari.control.*;
import fi.nls.oskari.domain.map.view.Bundle;
import fi.nls.oskari.log.LogFactory;
import fi.nls.oskari.map.view.*;
import fi.nls.oskari.view.modifier.ViewModifierManager;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.Cookie;

import fi.nls.oskari.domain.map.view.View;
import fi.nls.oskari.domain.map.view.ViewTypes;
import fi.nls.oskari.log.Logger;
import fi.nls.oskari.map.data.service.PublishedMapRestrictionService;
import fi.nls.oskari.map.data.service.PublishedMapRestrictionServiceImpl;
import fi.nls.oskari.view.modifier.ModifierException;
import fi.nls.oskari.view.modifier.ModifierParams;
import fi.nls.oskari.view.modifier.ViewModifier;
import fi.nls.oskari.control.view.modifier.bundle.BundleHandler;
import fi.nls.oskari.control.view.modifier.param.ParamControl;
import fi.nls.oskari.map.view.util.ViewHelper;
import fi.nls.oskari.util.ConversionHelper;
import fi.nls.oskari.util.JSONHelper;
import fi.nls.oskari.util.PropertyUtil;
import fi.nls.oskari.util.RequestHelper;
import fi.nls.oskari.util.ResponseHelper;

@OskariActionRoute("GetAppSetup")
public class GetAppSetupHandler extends ActionHandler {

    private static ViewService viewService = null;
    private static BundleService bundleService = null;
    private static PublishedMapRestrictionService restrictionService = null;

    private static final Logger log = LogFactory.getLogger(GetAppSetupHandler.class);

    public final static String PROPERTY_AJAXURL = "__AJAX_URL__";

    public static final String PARAM_VIEW_ID = "viewId";
    public static final String PARAM_OLD_ID = "oldId";
    public static final String PARAM_NO_SAVED_STATE = "noSavedState";
    public final static String VIEW_DATA = "viewData";
    public final static String STATE = "state";
    public static final String PARAM_SSL = "ssl";

    private static final String KEY_STARTUP = "startupSequence";
    private static final String KEY_CONFIGURATION = "configuration";

    public static final String COOKIE_SAVED_STATE = "oskaristate";

    private static final long DEFAULT_USERID = 10110;
    private static String UNRESTRICTED_USAGE_ROLE = "";

    // for adding admin extra bundle(s) when admin user in action
    private Bundle adminBundle = null;
    private Bundle adminLayerRightsBundle = null;

    private final Set<String> paramHandlers = new HashSet<String>();
    private final Map<String, BundleHandler> bundleHandlers = new HashMap<String, BundleHandler>();

    public void setViewService(final ViewService service) {
        viewService = service;
    }
    public void setBundleService(final BundleService service) {
        bundleService = service;
    }
    public void setPublishedMapRestrictionService(final PublishedMapRestrictionService service) {
        restrictionService = service;
    }

    public void init() {
        // setup services if they haven't been initialized
        if(viewService == null) {
            setViewService(new ViewServiceIbatisImpl());
        }
        if(bundleService == null) {
            setBundleService(new BundleServiceIbatisImpl());
        }
        if(restrictionService == null) {
            setPublishedMapRestrictionService(new PublishedMapRestrictionServiceImpl());
        }
        // Loads @OskariViewModifier annotated classes of type ParamHandler from classpath
        ParamControl.addDefaultControls();
        paramHandlers.addAll(ParamControl.getHandlerKeys());
        UNRESTRICTED_USAGE_ROLE = PropertyUtil.get("view.published.usage.unrestrictedRoles");

        // Loads @OskariViewModifier annotated classes of type BundleHandler from classpath
        final Map<String, BundleHandler> handlers = ViewModifierManager.getModifiersOfType(BundleHandler.class);
        for(String key : handlers.keySet()) {
            bundleHandlers.put(key, handlers.get(key));
        }

        adminBundle = bundleService.getBundleTemplateByName(ViewModifier.BUNDLE_ADMINLAYERSELECTOR);
        if(adminBundle == null) {
            log.warn("Couldn't get Admin-LayerSelector bundle template from DB!");
        }
        adminLayerRightsBundle = bundleService.getBundleTemplateByName(ViewModifier.BUNDLE_ADMINLAYERRIGHTS);
        if(adminLayerRightsBundle == null) {
            log.warn("Couldn't get Admin-LayerRights bundle template from DB!");
        }
    }

    public void handleAction(ActionParameters params) throws ActionException {

        // oldId => support for migrated published maps
        final long oldId = ConversionHelper.getLong(params.getHttpParam(PARAM_OLD_ID), -1);
        final boolean isOldPublishedMap = oldId != -1;

        final long defaultViewId = viewService.getDefaultViewId(params.getUser());
        long viewId = ConversionHelper.getLong(params.getHttpParam(PARAM_VIEW_ID), defaultViewId);

        // ignore saved state for old published maps, non-default views or if
        // explicit param is given
        boolean ignoreSavedState = isOldPublishedMap
                || viewId != defaultViewId
                || ConversionHelper.getBoolean(params.getHttpParam(PARAM_NO_SAVED_STATE), false);

        final String referer = RequestHelper.getDomainFromReferer(params
                .getHttpHeader("Referer"));

        final View view = getView(viewId, oldId);

        // couldn't get view
        if (view == null) {
            throw new ActionParamsException("Could not get View with id: " + viewId
                    + " and oldId: " + oldId);
        }
        // restore state from cookie if not
        if (!ignoreSavedState) {
            log.debug("Modifying map view if saved state is available");
            modifyView(view, getStateFromCookie(params
                    .getCookie(COOKIE_SAVED_STATE)));
        }

        // Strictly necessary only if oldId used
        viewId = view.getId();

        // Check user/permission
        final long creator = view.getCreator();
        final long userId = params.getUser().getId();
        if (view.isPublic() || creator == DEFAULT_USERID) {
            log.info("View ID:", viewId, "created by user", creator,
                    "is public, access granted for user with id", userId);
        } else if (creator == userId) {
            log.info("Creator", creator, "granted access to view with ID:",
                    viewId);
        } else {
            throw new ActionDeniedException("Denied access to view with ID: "
                    + viewId + " for user with id " + userId
                    + " - View created by user " + creator);
        }

        if (view.getType().equals(ViewTypes.PUBLISHED)) {
            // Check referrer
            final String pubDomain = view.getPubDomain();
            if (referer.endsWith("paikkatietoikkuna.fi")
                    || referer.endsWith("nls.fi")
                    || referer.endsWith(pubDomain)) {
                log.info("Granted access to published view in domain:",
                        pubDomain, "for referer", referer);
            } else {
                log.error("Referer: ", params.getHttpHeader("Referer"), " -> ",
                        referer);
                throw new ActionDeniedException(
                        "Denied access to published view in domain: "
                                + pubDomain + " for referer " + referer);
            }

            // Check View lock
            if (restrictionService.isPublishedMapLocked((int) viewId)) {
                throw new ActionDeniedException("View with id" + viewId
                        + "is locked!");
            }

            // Check usage count -
            // // FIXME: we cannot use the current user -> the publisher is the
            // one we are interested in!!!!
            /*
            if (!params.getUser().hasRole(UNRESTRICTED_USAGE_ROLE)) {
                final List<Integer> viewIdList = new ArrayList<Integer>();
                // get all view for view creator
                final List<View> viewList = viewService.getViewsForUser(view
                        .getCreator());
                for (View v : viewList) {
                    viewIdList.add((int) v.getId());
                }
                if (restrictionService.isServiceCountExceeded(viewIdList)) {
                    throw new ActionDeniedException(
                            "Denied access to published view" + viewId
                                    + " - service count for user" + userId
                                    + "exceeded!");
                }
            }
            */
        }

        // JSON presentation of view
        final JSONObject configuration = getConfiguration(view);
        final JSONArray startupSequence = getStartupSequence(view);

        // modify the loaded view before serving it if there are any control
        // parameters
        final ModifierParams modifierParams = new ModifierParams();
        modifierParams.setBaseAjaxUrl(getBaseAjaxUrl(params));
        modifierParams.setConfig(configuration);
        modifierParams.setLocale(params.getLocale());
        modifierParams.setReferer(referer);
        modifierParams.setClientIP(params.getClientIp());
        modifierParams.setUser(params.getUser());
        modifierParams.setViewType(view.getType());
        modifierParams.setViewId(view.getId());
        modifierParams.setStartupSequence(startupSequence);
        modifierParams.setOldPublishedMap(oldId != -1);
        modifierParams.setAjaxRouteParamName(ActionControl.PARAM_ROUTE);

        int locationModified = 0;
        for (String paramKey : paramHandlers) {
            final String value = params.getHttpParam(paramKey);
            modifierParams.setParamValue(value);
            try {
                if (value != null
                        && ParamControl.handleParam(paramKey, modifierParams)) {
                    locationModified++;
                    log.debug("Parameter", paramKey, "with value", value,
                            "modified map location");
                }
            } catch (ModifierException ex) {
                log.warn(ex, "Parameter couldn't be handled:", paramKey, "=",
                        value);
            }
            modifierParams.setLocationModified(locationModified > 0);
        }

        // rewrite bundle configurations f.ex. mapfull only lists layer ids ->
        // replace with full layer JSONs etc
        // mapfull modifier needs to know if location has been modified ->
        // disables geolocation bundle
        modifierParams.setLocationModified(locationModified > 0);
        // TODO: if we have modified location more than once, user gave
        // conflicting params, maybe notify about it?
        for (int i = 0; i < startupSequence.length(); i++) {
            final JSONObject bundle = (JSONObject) startupSequence.opt(i);
            final String bundleid = bundle.optString("bundlename");
            if (bundleHandlers.containsKey(bundleid)) {
                log.debug("Modifying bundle", bundleid);
                try {
                    bundleHandlers.get(bundleid).modifyBundle(modifierParams);
                } catch (ModifierException e) {
                    log.error(e, "Unable to modify bundle:", bundle);
                }
            }
        }

        // Add admin-layerselector/layer-rights bundle, if admin role and default view
        // TODO: check if we can assume ViewTypes.DEFAULT for this.
        if (params.getUser().isAdmin() && view.getType().equals(ViewTypes.DEFAULT)) {
            log.debug("Adding admin bundles for user", params.getUser());
            addBundle(modifierParams, ViewModifier.BUNDLE_ADMINLAYERSELECTOR, adminBundle);
            addBundle(modifierParams, ViewModifier.BUNDLE_ADMINLAYERRIGHTS, adminLayerRightsBundle);
        }

        // write response
        try {
            JSONObject appSetup = new JSONObject();
            appSetup.put(KEY_STARTUP, startupSequence);
            appSetup.put(KEY_CONFIGURATION, configuration);
            ResponseHelper.writeResponse(params, appSetup);
        } catch (JSONException jsonex) {
            throw new ActionException("Malformed startup sequence/config!",
                    jsonex);
        }
    }

    private JSONObject getConfiguration(final View view) throws ActionException {
        try {
            return ViewHelper.getConfiguration(view);
        } catch (ViewException e) {
            throw new ActionException("Couldn't get configuration", e);
        }
    }

    private JSONArray getStartupSequence(final View view)
            throws ActionException {
        try {
            return ViewHelper.getStartupSequence(view);
        } catch (ViewException e) {
            throw new ActionException("Couldn't get startup sequence", e);
        }
    }

    private View getView(final long viewId, final long oldId) {
        if (oldId > 0) {
            log.debug("Using old View ID :" + oldId);
            return viewService.getViewWithConfByOldId(oldId);
        } else {
            log.debug("Using View ID:" + viewId);
            return viewService.getViewWithConf(viewId);
        }
    }

    private JSONObject getStateFromCookie(javax.servlet.http.Cookie cookie) {
        if (cookie == null) {
            log.debug("Cookie state was <null>");
            return null;
        }
        // cookie view data
        try {
            String value = Cookie.unescape(cookie.getValue());
            if (!value.isEmpty()) {
                log.debug("Using cookie state:", value);
                return new JSONObject(value);
            }
        } catch (Exception je) {
            log.warn("Got cookie but couldnt transform to JSON", cookie);
        }
        return null;
    }

    private String getBaseAjaxUrl(final ActionParameters params) {
        final String baseAjaxUrl = PropertyUtil.get(params.getLocale(),
                PROPERTY_AJAXURL);
        if ("true".equals(params.getHttpParam(PARAM_SSL))) {
            return "/paikkatietoikkuna" + baseAjaxUrl;
        }
        return baseAjaxUrl;
    }

    private void modifyView(final View view, JSONObject myview) {
        if (myview == null) {
            return;
        }
        log.info("[GetAppSetupHandler] Fetching View from cookie", myview);
        // merge cookie state for mapfull
        try {
            // TODO: add error handling a bit more
            JSONObject viewdata = new JSONObject(myview.getString(VIEW_DATA));
            for ( Iterator<String> bundleIterator = viewdata.keys(); bundleIterator.hasNext(); ) {
                final String bundleName = bundleIterator.next();
                final String bundle = viewdata.getString(bundleName);
                String bundleState = null;
                if (!"{}".equals(bundle)) {
                    bundleState = new JSONObject(bundle).getString(STATE);
                    log.debug("Got state for bundle", bundleName, "- state:", bundleState);
                } else {
                    continue;
                }
                if (!"{}".equals(bundleState)) {
                    view.getBundleByName(bundleName).setState(bundleState);
                }
            }

            final String cookiestate = viewdata.getString(ViewModifier.BUNDLE_MAPFULL);
            final JSONObject jscookiestate = new JSONObject(cookiestate);
            final String cookiestatedata = jscookiestate.getString(STATE);
            // Check for empty layers array/is valid
            if (cookiestatedata.indexOf("[]") !=  -1) {
                view.getBundleByName(ViewModifier.BUNDLE_MAPFULL).setState(
                        cookiestatedata);
            }
        } catch (Exception ex) {
            log.warn("Error parsing cookie JSON:", myview, ex);
        }
    }

    private void addBundle(final ModifierParams params, final String id, final Bundle bundle) {

        if(bundle == null) {
            // admin bundle init failed. See init().
            log.debug("Tried to insert bundle but it isn't initialized. Id:", id);
            return;
        }
        try {
            // add to startup sequence
            params.getStartupSequence().put(JSONHelper.createJSONObject(bundle.getStartup()));
            // add initial config/state
            final JSONObject bundleConfig = new JSONObject();
            bundleConfig.put(ViewModifier.KEY_CONF,
                    JSONHelper.createJSONObject(bundle.getConfig()));
            bundleConfig.put(ViewModifier.KEY_STATE,
                    JSONHelper.createJSONObject(bundle.getState()));
            params.getConfig().put(id, bundleConfig);
        } catch (Exception e) {
            log.error(e, "Failed to add", id, "bundle to startup sequence:",
                    bundle);
        }
    }
}