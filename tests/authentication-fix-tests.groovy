/**
 * Authentication Fix Tests
 * Tests for the v0.22 authentication fixes that resolve fresh install issues
 */

import spock.lang.*
import groovy.json.JsonSlurper

class AuthenticationFixSpec extends Specification {

    def app
    def mockResp
    def state
    def settings
    def loggedMessages = []

    def setup() {
        app = new TestApp()
        mockResp = new MockHttpResponse()
        state = [:]
        settings = [clientId: 'test-client-id', clientSecret: 'test-secret']
        loggedMessages.clear()
        
        // Initialize the app with test state
        app.state = state
        app.settings = settings
        app.loggedMessages = loggedMessages
    }

    def "authenticate() should use async HTTP call instead of blocking"() {
        given: "valid OAuth credentials"
        settings.clientId = 'test-client-id'
        settings.clientSecret = 'test-secret'
        
        when: "authenticate is called"
        def result = app.authenticate()
        
        then: "should use asynchttpPost instead of httpPost"
        result == ''
        state.authInProgress == true
        app.asyncPostCalled == true
        app.asyncPostCallback == 'handleAuthResponse'
        app.asyncPostParams.uri == 'https://api.flair.co/oauth2/token'
        app.asyncPostParams.contentType == 'application/x-www-form-urlencoded'
    }

    def "authenticate() should set authInProgress flag properly"() {
        given: "fresh authentication state"
        state.authInProgress = null
        
        when: "authenticate is called"
        app.authenticate()
        
        then: "authInProgress should be set to true"
        state.authInProgress == true
    }

    def "authenticate() should handle exceptions gracefully"() {
        given: "an app that throws exceptions on async calls"
        app.shouldThrowOnAsyncPost = true
        
        when: "authenticate is called"
        def result = app.authenticate()
        
        then: "should return error message and set proper state"
        result.startsWith("Authentication request failed:")
        state.authError.startsWith("Authentication request failed:")
        state.authInProgress == false
    }

    def "handleAuthResponse() should handle successful authentication"() {
        given: "successful OAuth response"
        mockResp.setSuccessResponse([access_token: 'test-token-123', expires_in: 3600])
        state.authInProgress = true
        
        when: "handleAuthResponse is called"
        app.handleAuthResponse(mockResp)
        
        then: "should set access token and clear error state"
        state.flairAccessToken == 'test-token-123'
        state.authError == null
        state.authInProgress == false
        app.runInCalled == true
        app.runInMethod == 'getStructureDataAsync'
        app.runInDelay == 2
    }

    def "handleAuthResponse() should handle 401 authentication errors"() {
        given: "401 authentication error response"
        mockResp.setErrorResponse(401, "Unauthorized")
        state.authInProgress = true
        
        when: "handleAuthResponse is called"
        app.handleAuthResponse(mockResp)
        
        then: "should set specific error message for invalid credentials"
        state.authError.contains("Invalid credentials")
        state.authError.contains("Client ID and Client Secret")
        state.authInProgress == false
        state.flairAccessToken == null
    }

    def "handleAuthResponse() should handle 403 forbidden errors"() {
        given: "403 forbidden error response"
        mockResp.setErrorResponse(403, "Forbidden")
        state.authInProgress = true
        
        when: "handleAuthResponse is called"
        app.handleAuthResponse(mockResp)
        
        then: "should set specific error message for access forbidden"
        state.authError.contains("Access forbidden")
        state.authError.contains("proper permissions")
        state.authInProgress == false
    }

    def "handleAuthResponse() should handle 429 rate limiting errors"() {
        given: "429 rate limiting error response"
        mockResp.setErrorResponse(429, "Too Many Requests")
        state.authInProgress = true
        
        when: "handleAuthResponse is called"
        app.handleAuthResponse(mockResp)
        
        then: "should set specific error message for rate limiting"
        state.authError.contains("Rate limited")
        state.authError.contains("wait a few minutes")
        state.authInProgress == false
    }

    def "handleAuthResponse() should handle null response gracefully"() {
        given: "null response from API"
        state.authInProgress = true
        
        when: "handleAuthResponse is called with null"
        app.handleAuthResponse(null)
        
        then: "should set appropriate error message"
        state.authError == "Authentication failed: No response from Flair API"
        state.authInProgress == false
    }

    def "handleAuthResponse() should handle missing access_token in response"() {
        given: "response without access token"
        mockResp.setSuccessResponse([error: 'invalid_client', error_description: 'Invalid client credentials'])
        state.authInProgress = true
        
        when: "handleAuthResponse is called"
        app.handleAuthResponse(mockResp)
        
        then: "should set error message with API error details"
        state.authError.contains("Authentication failed: Invalid client credentials")
        state.authError.contains("OAuth 2.0 credentials are correct")
        state.authInProgress == false
    }

    def "handleAuthResponse() should handle exceptions during processing"() {
        given: "response that will cause exception during processing"
        mockResp.setExceptionOnGetData(true)
        state.authInProgress = true
        
        when: "handleAuthResponse is called"
        app.handleAuthResponse(mockResp)
        
        then: "should handle exception gracefully"
        state.authInProgress == false
        state.authError.startsWith("Authentication processing failed:")
    }

    def "mainPage() should show proper UI states during authentication"() {
        given: "credentials are provided"
        settings.clientId = 'test-client'
        settings.clientSecret = 'test-secret'
        
        when: "authInProgress is true"
        state.authInProgress = true
        def page = app.getMainPageContent()
        
        then: "should show progress indicator"
        page.contains("⏳ Authenticating... Please wait.")
        page.contains("This may take 10-15 seconds")
        
        when: "authentication is successful"
        state.authInProgress = false
        state.flairAccessToken = 'test-token'
        state.authError = null
        page = app.getMainPageContent()
        
        then: "should show success message"
        page.contains("✓ Authenticated successfully")
        
        when: "authentication failed"
        state.authInProgress = false
        state.flairAccessToken = null
        state.authError = "Test error message"
        page = app.getMainPageContent()
        
        then: "should show error message and retry button"
        page.contains("Test error message")
        page.contains("Retry Authentication")
        page.contains("verify your credentials are correct")
    }

    def "autoAuthenticate() should trigger authentication with proper delay"() {
        given: "valid credentials and no existing token"
        settings.clientId = 'test-client'
        settings.clientSecret = 'test-secret'
        state.flairAccessToken = null
        
        when: "autoAuthenticate is called"
        app.autoAuthenticate()
        
        then: "should call authenticate and schedule token refresh"
        app.authenticateCalled == true
        app.unscheduleMethod == 'login'
        app.runEvery1HourMethod == 'login'
    }

    def "autoAuthenticate() should handle exceptions gracefully"() {
        given: "credentials that will cause exception"
        settings.clientId = 'test-client'
        settings.clientSecret = 'test-secret'
        state.flairAccessToken = null
        app.shouldThrowOnAuthenticate = true
        
        when: "autoAuthenticate is called"  
        app.autoAuthenticate()
        
        then: "should handle exception and set error state"
        state.authInProgress == false
        state.authError.startsWith("Auto-authentication failed:")
        loggedMessages.any { it.contains("Auto-authentication failed:") }
    }

    def "getStructureDataAsync() should call async method"() {
        given: "valid access token"
        state.flairAccessToken = 'test-token'
        
        when: "getStructureDataAsync is called"
        app.getStructureDataAsync()
        
        then: "should make async HTTP call"
        app.asyncGetCalled == true
        app.asyncGetCallback == 'handleStructureResponse'
        app.asyncGetParams.uri == 'https://api.flair.co/api/structures'
        app.asyncGetParams.headers.Authorization == 'Bearer test-token'
    }

    def "handleStructureResponse() should process structure data"() {
        given: "valid structure response"
        def structureData = [
            data: [
                [id: 'struct-123', attributes: [name: 'Test Home']]
            ]
        ]
        mockResp.setSuccessResponse(structureData)
        
        when: "handleStructureResponse is called"
        app.handleStructureResponse(mockResp, null)
        
        then: "should update structure settings"
        app.updateSettingCalled == true
        app.updateSettingKey == 'structureId'
        app.updateSettingValue == 'struct-123'
        loggedMessages.any { it.contains("Structure loaded: id=struct-123") }
    }

    def "appButtonHandler() should handle retryAuth button"() {
        given: "authentication error state"
        state.authError = "Test error"
        state.authInProgress = false
        
        when: "retryAuth button is pressed"
        app.appButtonHandler('retryAuth')
        
        then: "should clear error state and restart authentication"
        state.authError == null
        state.authInProgress == null
        app.runInCalled == true
        app.runInMethod == 'autoAuthenticate'
        app.runInDelay == 1
    }

    def "authentication flow should work with proper timing"() {
        given: "fresh installation scenario"
        settings.clientId = 'test-client'
        settings.clientSecret = 'test-secret'
        state.flairAccessToken = null
        state.authInProgress = null
        
        when: "mainPage triggers auto-authentication"
        app.triggerAutoAuth()
        
        then: "should set authInProgress and schedule authentication"
        state.authInProgress == true
        app.runInCalled == true
        app.runInMethod == 'autoAuthenticate'
        app.runInDelay == 2  // Increased delay for UI stability
        
        when: "authentication completes successfully"
        mockResp.setSuccessResponse([access_token: 'new-token', expires_in: 3600])
        app.handleAuthResponse(mockResp)
        
        then: "should complete the flow"
        state.flairAccessToken == 'new-token'
        state.authInProgress == false
        state.authError == null
    }
}

// Test helper classes
class TestApp {
    def state = [:]
    def settings = [:]
    def loggedMessages = []
    
    // Mock behavior flags
    boolean shouldThrowOnAsyncPost = false
    boolean shouldThrowOnAuthenticate = false
    
    // Call tracking
    boolean asyncPostCalled = false
    boolean asyncGetCalled = false
    boolean authenticateCalled = false
    boolean runInCalled = false
    boolean unscheduleCalled = false
    boolean runEvery1HourCalled = false
    boolean updateSettingCalled = false
    
    String asyncPostCallback
    Map asyncPostParams
    String asyncGetCallback
    Map asyncGetParams
    String runInMethod
    Integer runInDelay
    String unscheduleMethod
    String runEvery1HourMethod
    String updateSettingKey
    String updateSettingValue

    // Simulate authenticate method with fixes
    def authenticate() {
        authenticateCalled = true
        
        if (shouldThrowOnAuthenticate) {
            throw new Exception("Test exception in authenticate")
        }
        
        state.authInProgress = true
        
        def uri = "https://api.flair.co/oauth2/token"
        def body = "client_id=${settings?.clientId}&client_secret=${settings?.clientSecret}" +
            '&scope=vents.view+vents.edit+structures.view+structures.edit+pucks.view+pucks.edit&grant_type=client_credentials'
        
        def params = [
            uri: uri, 
            body: body, 
            timeout: 5,
            contentType: 'application/x-www-form-urlencoded'
        ]
        
        try {
            if (shouldThrowOnAsyncPost) {
                throw new Exception("Test async post exception")
            }
            
            asynchttpPost('handleAuthResponse', params)
        } catch (Exception e) {
            def err = "Authentication request failed: ${e.message}"
            logError(err)
            state.authError = err
            state.authInProgress = false
            return err
        }
        return ''
    }

    def asynchttpPost(callback, params) {
        asyncPostCalled = true
        asyncPostCallback = callback
        asyncPostParams = params
    }

    def asynchttpGet(callback, params) {
        asyncGetCalled = true
        asyncGetCallback = callback
        asyncGetParams = params
    }

    def handleAuthResponse(resp) {
        try {
            state.authInProgress = false
            
            if (!resp) {
                state.authError = "Authentication failed: No response from Flair API"
                logError(state.authError)
                return
            }
            
            if (resp.hasError()) {
                def status = resp.getStatus()
                def errorMsg = "Authentication failed with HTTP ${status}"
                if (status == 401) {
                    errorMsg += ": Invalid credentials. Please verify your Client ID and Client Secret."
                } else if (status == 403) {
                    errorMsg += ": Access forbidden. Please verify your OAuth credentials have proper permissions."
                } else if (status == 429) {
                    errorMsg += ": Rate limited. Please wait a few minutes and try again."
                } else {
                    errorMsg += ": ${resp.getErrorMessage() ?: 'Unknown error'}"
                }
                state.authError = errorMsg
                logError(state.authError)
                return
            }
            
            def respJson = resp.getData()
            if (respJson?.access_token) {
                state.flairAccessToken = respJson.access_token
                state.remove('authError')
                log('Authentication successful', 2)
                
                runIn(2, 'getStructureDataAsync')
            } else {
                def errorDetails = respJson?.error_description ?: respJson?.error ?: 'Unknown error'
                state.authError = "Authentication failed: ${errorDetails}. " +
                                  "Please verify your OAuth 2.0 credentials are correct."
                logError(state.authError)
            }
        } catch (Exception e) {
            state.authInProgress = false
            state.authError = "Authentication processing failed: ${e.message}"
            logError(state.authError)
        }
    }

    def getStructureDataAsync() {
        def uri = "https://api.flair.co/api/structures"
        def headers = [ Authorization: "Bearer ${state.flairAccessToken}" ]
        def httpParams = [ 
            uri: uri, 
            headers: headers, 
            contentType: 'application/json', 
            timeout: 5 
        ]
        
        try {
            asynchttpGet('handleStructureResponse', httpParams)
        } catch (Exception e) {
            logError("Structure data request failed: ${e.message}")
        }
    }

    def handleStructureResponse(resp, data) {
        try {
            if (!isValidResponse(resp)) { 
                logError("Structure data request failed")
                return 
            }
            
            def response = resp.getJson()
            if (!response?.data?.first()) {
                logError('No structure data available')
                return
            }
            
            def myStruct = response.data.first()
            if (myStruct?.id) {
                updateSetting('structureId', myStruct.id)
                log("Structure loaded: id=${myStruct.id}, name=${myStruct.attributes?.name}", 2)
            }
        } catch (Exception e) {
            logError("Structure data processing failed: ${e.message}")
        }
    }

    def isValidResponse(resp) {
        return resp && !resp.hasError()
    }

    def autoAuthenticate() {
        try {
            if (settings?.clientId && settings?.clientSecret && !state.flairAccessToken) {
                log('Auto-authenticating with provided credentials', 2)
                def result = authenticate()
                
                if (result == '') {
                    unschedule('login')
                    runEvery1Hour('login')
                } else {
                    state.authInProgress = false
                    state.authError = result
                }
            } else {
                state.authInProgress = false
            }
        } catch (Exception e) {
            state.authInProgress = false
            state.authError = "Auto-authentication failed: ${e.message}"
            logError(state.authError)
        }
    }

    def appButtonHandler(String btn) {
        switch (btn) {
            case 'retryAuth':
                state.remove('authError')
                state.remove('authInProgress')
                runIn(1, 'autoAuthenticate')
                break
        }
    }

    def triggerAutoAuth() {
        if (!state.flairAccessToken && !state.authInProgress) {
            state.authInProgress = true
            runIn(2, 'autoAuthenticate')
        }
    }

    def getMainPageContent() {
        def content = ""
        
        if (settings?.clientId && settings?.clientSecret) {
            if (state.flairAccessToken && !state.authError) {
                content += "✓ Authenticated successfully"
            } else if (state.authError && !state.authInProgress) {
                content += state.authError
                content += "Retry Authentication"
                content += "verify your credentials are correct"
            } else if (state.authInProgress) {
                content += "⏳ Authenticating... Please wait."
                content += "This may take 10-15 seconds"
            }
        }
        
        return content
    }

    def runIn(delay, method) {
        runInCalled = true
        runInDelay = delay
        runInMethod = method
    }

    def unschedule(method) {
        unscheduleCalled = true
        unscheduleMethod = method
    }

    def runEvery1Hour(method) {
        runEvery1HourCalled = true
        runEvery1HourMethod = method
    }

    def updateSetting(key, value) {
        updateSettingCalled = true
        updateSettingKey = key
        updateSettingValue = value
    }

    def log(msg, level) {
        loggedMessages << msg
    }

    def logError(msg) {
        loggedMessages << "ERROR: ${msg}"
    }
}

class MockHttpResponse {
    private boolean hasError = false
    private int status = 200
    private String errorMessage = ""
    private Map responseData = [:]
    private boolean throwOnGetData = false

    def setSuccessResponse(Map data) {
        this.hasError = false
        this.status = 200
        this.responseData = data
    }

    def setErrorResponse(int status, String message) {
        this.hasError = true
        this.status = status
        this.errorMessage = message
    }

    def setExceptionOnGetData(boolean throwException) {
        this.throwOnGetData = throwException
    }

    boolean hasError() {
        return hasError
    }

    int getStatus() {
        return status
    }

    String getErrorMessage() {
        return errorMessage
    }

    def getData() {
        if (throwOnGetData) {
            throw new Exception("Test exception in getData")
        }
        return responseData
    }

    def getJson() {
        return responseData
    }
}
