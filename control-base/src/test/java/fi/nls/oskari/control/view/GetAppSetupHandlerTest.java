package fi.nls.oskari.control.view;

import fi.mml.map.mapwindow.service.db.LayerClassService;
import fi.mml.map.mapwindow.service.db.LayerClassServiceIbatisImpl;
import fi.mml.map.mapwindow.util.MapLayerWorker;
import fi.mml.portti.service.db.permissions.PermissionsService;
import fi.mml.portti.service.db.permissions.PermissionsServiceIbatisImpl;
import fi.nls.oskari.control.ActionParameters;
import fi.nls.oskari.control.view.modifier.bundle.BundleHandler;
import fi.nls.oskari.control.view.modifier.param.CoordinateParamHandler;
import fi.nls.oskari.domain.GuestUser;
import fi.nls.oskari.domain.User;
import fi.nls.oskari.domain.map.view.View;
import fi.nls.oskari.domain.map.view.ViewTypes;
import fi.nls.oskari.domain.map.wms.LayerClass;
import fi.nls.oskari.map.data.service.PublishedMapRestrictionService;
import fi.nls.oskari.map.data.service.PublishedMapRestrictionServiceImpl;
import fi.nls.oskari.map.view.BundleService;
import fi.nls.oskari.map.view.BundleServiceIbatisImpl;
import fi.nls.oskari.map.view.ViewService;
import fi.nls.oskari.map.view.ViewServiceIbatisImpl;
import fi.nls.oskari.view.modifier.ViewModifier;
import fi.nls.oskari.control.view.modifier.param.WFSHighlightParamHandler;
import fi.nls.oskari.view.modifier.ViewModifierManager;
import fi.nls.test.control.JSONActionRouteTest;
import fi.nls.test.util.ResourceHelper;
import fi.nls.test.view.BundleTestHelper;
import fi.nls.test.view.ViewTestHelper;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.Matchers.*;
import static org.mockito.Mockito.*;
import static org.powermock.api.mockito.PowerMockito.whenNew;

/**
 * Created with IntelliJ IDEA.
 * User: SMAKINEN
 * Date: 31.5.2013
 * Time: 12:50
 * To change this template use File | Settings | File Templates.
 */
@RunWith(PowerMockRunner.class)
@PrepareForTest(value = {WFSHighlightParamHandler.class, MapLayerWorker.class})
public class GetAppSetupHandlerTest extends JSONActionRouteTest {

    final private GetAppSetupHandler handler = new GetAppSetupHandler();

    private ViewService viewService = null;
    private BundleService bundleService = null;
    private PublishedMapRestrictionService restrictionService = null;

    @Before
    public void setUp() throws Exception {

        mockViewService();
        mockBundleService();
        restrictionService = mock(PublishedMapRestrictionServiceImpl.class);
        mockInternalServices();

        handler.setViewService(viewService);
        handler.setBundleService(bundleService);
        handler.setPublishedMapRestrictionService(restrictionService);

        /*
        PowerMockito.mockStatic(ParamControl.class);
        doNothing().when(ParamControl.class);
        */
        handler.init();
    }


    /**
     * Ignored since tests not finished yet
     */
    @Test
    public void testWithNoViewIdAndGuestUser() throws Exception {
        final ActionParameters params = createActionParams();
        handler.handleAction(params);

        // check that view was loaded vith id 2 as we mocked the default view to be for guest user
        verify(viewService, times(1)).getViewWithConf(2);

        // check that the guest view matches
        verifyResponseContent(ResourceHelper.readJSONResource("GetAppSetupHandlerTest-view-guest.json", this));
    }

    /**
     * Ignored since tests not finished yet
     */
    @Test
    public void testWithNoViewIdAndLoggedInUser() throws Exception {
        final ActionParameters params = createActionParams(getLoggedInUser());
        handler.handleAction(params);

        // check that view was loaded vith id 1 as we mocked the default view to be logged in user
        verify(viewService, times(1)).getViewWithConf(1);

        // check that the user is written to the config
        verifyResponseContent(ResourceHelper.readJSONResource("GetAppSetupHandlerTest-view-loggedin.json", this));
    }

    @Test
    public void testWithViewIdGiven() throws Exception {
        // setup params
        Map<String, String> parameters = new HashMap<String, String>();
        parameters.put(GetAppSetupHandler.PARAM_VIEW_ID, "3");
        // TODO: setup a cookie with state and see that it shouldn't change the view since a specific non-default view was requested
        // TODO: create a test without giving viewId and see that the cookie affects it
        final ActionParameters params = createActionParams(parameters);
        handler.handleAction(params);

        // check that view was loaded vith id 3 as requested
        verify(viewService, times(1)).getViewWithConf(3);

        // check that the response matches expected
        verifyResponseContent(ResourceHelper.readJSONResource("GetAppSetupHandlerTest-view-3.json", this));
    }

    @Test
    public void testWithCoordinateParameterGiven() throws Exception {
        // setup params
        Map<String, String> parameters = new HashMap<String, String>();
        CoordinateParamHandler h = new CoordinateParamHandler();
        parameters.put(h.getName(), "123_456");

        final ActionParameters params = createActionParams(parameters);
        handler.handleAction(params);

        // coordinates should be set as in param and geolocation plugin should have been removed from config
        verifyResponseContent(ResourceHelper.readJSONResource("GetAppSetupHandlerTest-coordinate-params.json", this));
    }

    @Test
    public void testWithOldIdGiven() throws Exception {
        // setup params
        Map<String, String> parameters = new HashMap<String, String>();
        parameters.put(GetAppSetupHandler.PARAM_VIEW_ID, "456");
        parameters.put(GetAppSetupHandler.PARAM_OLD_ID, "123");
        // TODO: setup a cookie with state and see that it shouldn't change the view since a migrated view was requested
        final ActionParameters params = createActionParams(parameters);
        handler.handleAction(params);

        // check that view was not loaded with id, but with old Id
        verify(viewService, never()).getViewWithConf(anyLong());
        verify(viewService, times(1)).getViewWithConfByOldId(123);
    }
    /* *********************************************
     * Service mocks
     * ********************************************
     */
    private void mockViewService() {

        viewService = mock(ViewServiceIbatisImpl.class);
        // id 2 for guest user
        doReturn(2L).when(viewService).getDefaultViewId(getGuestUser());
        // id 1 for logged in user
        doReturn(1L).when(viewService).getDefaultViewId(getLoggedInUser());
        final View dummyView = ViewTestHelper.createMockView("framework.mapfull");
        dummyView.setType(ViewTypes.USER);
        doReturn(dummyView).when(viewService).getViewWithConfByOldId(anyLong());
        doReturn(dummyView).when(viewService).getViewWithConf(anyLong());

        // TODO: mock view loading
        /**
         * fi.nls.oskari.control.ActionException: Could not get View with id: 2 and oldId: -1
         *      at fi.nls.oskari.control.view.GetAppSetupHandler.handleAction(GetAppSetupHandler.java:136)
         */
    }

    private void mockBundleService() throws Exception {

        bundleService = mock(BundleServiceIbatisImpl.class);
        doReturn(
                BundleTestHelper.loadBundle("integration.admin-layerselector")
        ).when(bundleService).getBundleTemplateByName(ViewModifier.BUNDLE_ADMINLAYERSELECTOR);

        doReturn(
                BundleTestHelper.loadBundle("framework.postprocessor")
        ).when(bundleService).getBundleTemplateByName(ViewModifier.BUNDLE_POSTPROCESSOR);

        // return mocked  bundle service if a new one is created (in paramhandlers for example)
        // classes doing this must be listed in PrepareForTest annotation
        whenNew(BundleServiceIbatisImpl.class).withNoArguments().
                thenAnswer(new Answer<Object>() {
                    public Object answer(InvocationOnMock invocation) throws Throwable {
                        return bundleService;
                    }
                });
    }

    private void mockInternalServices() throws Exception {

        final PermissionsService service = mock(PermissionsServiceIbatisImpl.class);
        doReturn(
                Collections.emptyList()
        ).when(service).getResourcesWithGrantedPermissions(anyString(), any(User.class), anyString());

        // return mocked  bundle service if a new one is created (in paramhandlers for example)
        // classes doing this must be listed in PrepareForTest annotation
        whenNew(PermissionsServiceIbatisImpl.class).withNoArguments().
                thenAnswer(new Answer<Object>() {
                    public Object answer(InvocationOnMock invocation) throws Throwable {
                        return service;
                    }
                });


/*
    public static JSONObject getSelectedLayersStructure(List<String> layerList,
                                                        User user, String lang, String remoteIp, boolean isPublished) {
                                                        */
        // TODO: mock MapLayerWorker.getSelectedLayersStructure() instead to return a valid JSON structure
        final LayerClassService layerClassService = mock(LayerClassServiceIbatisImpl.class);
        LayerClass layerClass = mock(LayerClass.class);
        doReturn(
                layerClass
        ).when(layerClassService).findOrganizationalStructureByClassId(anyInt());

        // return mocked  bundle service if a new one is created (in paramhandlers for example)
        // classes doing this must be listed in PrepareForTest annotation
        whenNew(LayerClassServiceIbatisImpl.class).withNoArguments().
                thenAnswer(new Answer<Object>() {
                    public Object answer(InvocationOnMock invocation) throws Throwable {
                        return layerClassService;
                    }
                });
    }

}
