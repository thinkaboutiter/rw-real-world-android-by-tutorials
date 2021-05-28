package com.raywenderlich.android.petsave.common.data.api.interceptors

import android.os.Build
import com.google.common.truth.Truth.assertThat
import com.raywenderlich.android.petsave.common.data.api.ApiConstants
import com.raywenderlich.android.petsave.common.data.api.ApiParameters
import com.raywenderlich.android.petsave.common.data.api.utils.JsonReader
import com.raywenderlich.android.petsave.common.data.preferences.Preferences
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.threeten.bp.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE, sdk = [Build.VERSION_CODES.P])
class AuthenticationInterceptorTest {

  private lateinit var preferences: Preferences
  private lateinit var mockWebServer: MockWebServer
  private lateinit var authenticationInterceptor: AuthenticationInterceptor
  private lateinit var okHttpClient: OkHttpClient

  private val endpointSeparator = "/"
  private val animalsEndpointPath = endpointSeparator + ApiConstants.ANIMALS_ENDPOINT
  private val authEndpointPath = endpointSeparator + ApiConstants.AUTH_ENDPOINT
  private val validToken = "validToken"
  private val expiredToken = "expiredToken"

  @Before
  fun setup() {
    preferences = mock(Preferences::class.java)

    mockWebServer = MockWebServer()
    mockWebServer.start(8080)

    authenticationInterceptor = AuthenticationInterceptor(preferences)
    okHttpClient = OkHttpClient().newBuilder().addInterceptor(authenticationInterceptor).build()
  }

  @After
  fun teardown() {
    mockWebServer.shutdown()
  }

  @Test
  fun authenticationInterceptor_validToken() {
    //Given
    `when`(preferences.getToken()).thenReturn(validToken)
    `when`(preferences.getTokenExpirationTime()).thenReturn(
        Instant.now().plusSeconds(3600).epochSecond
    )

    mockWebServer.dispatcher = getDispatcherForValidToken()

    // When
    okHttpClient.newCall(
        Request.Builder().url(mockWebServer.url(ApiConstants.ANIMALS_ENDPOINT)).build()
    ).execute()

    // Then
    val request = mockWebServer.takeRequest()

    with(request) {
      assertThat(method).isEqualTo("GET")
      assertThat(path).isEqualTo(animalsEndpointPath)
      assertThat(getHeader(ApiParameters.AUTH_HEADER)).isEqualTo(ApiParameters.TOKEN_TYPE + validToken)
    }
  }

  @Test
  fun authenticatorInterceptor_expiredToken() {
    // Given
    `when`(preferences.getToken()).thenReturn(expiredToken)
    `when`(preferences.getTokenExpirationTime()).thenReturn(
        Instant.now().minusSeconds(3600).epochSecond
    )

    mockWebServer.dispatcher = getDispatcherForExpiredToken()

    // When
    okHttpClient.newCall(
        Request.Builder().url(mockWebServer.url(ApiConstants.ANIMALS_ENDPOINT)).build()
    ).execute()

    // Then
    val tokenRequest = mockWebServer.takeRequest()
    val animalsRequest = mockWebServer.takeRequest()

    with(tokenRequest) {
      assertThat(method).isEqualTo("POST")
      assertThat(path).isEqualTo(authEndpointPath)
    }

    val inOrder = inOrder(preferences)

    inOrder.verify(preferences).getToken()
    inOrder.verify(preferences).putToken(validToken)

    verify(preferences, times(1)).getToken()
    verify(preferences, times(1)).putToken(validToken)
    verify(preferences, times(1)).getTokenExpirationTime()
    verify(preferences, times(1)).putTokenExpirationTime(anyLong())
    verify(preferences, times(1)).putTokenType(ApiParameters.TOKEN_TYPE.trim())
    verifyNoMoreInteractions(preferences)

    with(animalsRequest) {
      assertThat(method).isEqualTo("GET")
      assertThat(path).isEqualTo(animalsEndpointPath)
      assertThat(getHeader(ApiParameters.AUTH_HEADER)).isEqualTo(ApiParameters.TOKEN_TYPE + validToken)
    }
  }

  private fun getDispatcherForValidToken() = object : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse {
      return when (request.path) {
        animalsEndpointPath -> { MockResponse().setResponseCode(200) }
        else -> { MockResponse().setResponseCode(404) }
      }
    }
  }

  private fun getDispatcherForExpiredToken() = object : Dispatcher() {
    override fun dispatch(request: RecordedRequest): MockResponse {
      return when (request.path) {
        authEndpointPath -> {
          MockResponse().setResponseCode(200).setBody(JsonReader.getJson("validToken.json"))
        }
        animalsEndpointPath -> { MockResponse().setResponseCode(200) }
        else -> { MockResponse().setResponseCode(404) }
      }
    }
  }
}