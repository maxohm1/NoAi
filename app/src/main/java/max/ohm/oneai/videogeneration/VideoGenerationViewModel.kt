package max.ohm.oneai.videogeneration

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import max.ohm.oneai.videogeneration.network.VideoApiClient
import max.ohm.oneai.videogeneration.network.VideoGenerationRequest

class VideoGenerationViewModel : ViewModel() {

    private val _prompt = MutableStateFlow("")
    val prompt: StateFlow<String> = _prompt

    private val _videoUrl = MutableStateFlow<String?>(null)
    val videoUrl: StateFlow<String?> = _videoUrl

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _elapsedTimeInSeconds = MutableStateFlow(0L)
    val elapsedTimeInSeconds: StateFlow<Long> = _elapsedTimeInSeconds

    private val _totalGenerationTimeInSeconds = MutableStateFlow<Long?>(null)
    val totalGenerationTimeInSeconds: StateFlow<Long?> = _totalGenerationTimeInSeconds

    private var timerJob: Job? = null
    private var generationStartTime: Long = 0L
    private var currentTaskId: String? = null
    private var retryCount = 0
    private val maxRetries = 3

    companion object {
        private const val TAG = "VideoGenViewModel"
    }

    fun setPrompt(newPrompt: String) {
        _prompt.value = newPrompt
    }

    private fun startTimer() {
        _elapsedTimeInSeconds.value = 0L
        _totalGenerationTimeInSeconds.value = null
        generationStartTime = System.currentTimeMillis()
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (_isLoading.value) {
                _elapsedTimeInSeconds.value = (System.currentTimeMillis() - generationStartTime) / 1000
                delay(1000) 
            }
        }
    }

    private fun stopTimerAndSetTotalTime(isSuccess: Boolean) {
        timerJob?.cancel()
        val endTime = System.currentTimeMillis()
        val durationSeconds = (endTime - generationStartTime) / 1000
        _elapsedTimeInSeconds.value = durationSeconds
        if (isSuccess) {
            _totalGenerationTimeInSeconds.value = durationSeconds
        }
        _isLoading.value = false
    }

    fun generateVideo() {
        Log.d(TAG, "generateVideo called. Current prompt: ${_prompt.value}")
        _isLoading.value = true
        _error.value = null
        _videoUrl.value = null
        retryCount = 0
        currentTaskId = null
        startTimer()
        Log.d(TAG, "isLoading set to true, videoUrl set to null, timer started.")

        if (!VideoGenApiKey.validateCredentials()) {
            _error.value = "Invalid API credentials. Please check your API key and Group ID."
            stopTimerAndSetTotalTime(false)
            return
        }

        generateVideoInternal()
    }
    
    private fun generateVideoInternal() {
        viewModelScope.launch {
            var success = false
            try {
                val authorization = "Bearer ${VideoGenApiKey.API_KEY}"
                val request = VideoGenerationRequest(model = "T2V-01-Director", prompt = _prompt.value)
                Log.d(TAG, "Sending video generation request with prompt: ${_prompt.value}, attempt: ${retryCount + 1}/${maxRetries + 1}")
                
                val response = VideoApiClient.apiService.generateVideo(authorization, request)

                if (response.isSuccessful) {
                    val responseBody = response.body()
                    if (responseBody?.taskId != null) {
                        currentTaskId = responseBody.taskId
                        Log.d(TAG, "Task ID received: ${responseBody.taskId}. Polling video status.")
                        pollVideoStatus(responseBody.taskId)
                        return@launch
                    } else {
                        Log.e(TAG, "Task ID not received. Full response: ${response.body().toString()}")
                        handleError("Task ID not received from generation API.")
                    }
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "Error generating video: ${response.code()}, Body: $errorBody")
                    handleError("Error generating video (HTTP ${response.code()}): $errorBody")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error in generateVideo: ${e.message}", e)
                handleError("Network error (generateVideo): ${e.message}")
            } finally {
                if (_isLoading.value && !success) {
                    stopTimerAndSetTotalTime(success)
                }
            }
        }
    }
    
    private fun handleError(errorMessage: String) {
        if (retryCount < maxRetries) {
            retryCount++
            Log.w(TAG, "Retrying video generation after error: $errorMessage (Attempt ${retryCount}/$maxRetries)")
            viewModelScope.launch {
                delay(2000) // Wait 2 seconds before retrying
                generateVideoInternal()
            }
        } else {
            Log.e(TAG, "Failed after $maxRetries retries: $errorMessage")
            _error.value = "$errorMessage\nFailed after $maxRetries retries."
        }
    }

    private suspend fun pollVideoStatus(taskId: String) {
        Log.d(TAG, "pollVideoStatus called for taskId: $taskId")
        val authorization = "Bearer ${VideoGenApiKey.API_KEY}"
        var attempts = 0
        val maxAttempts = 60
        var success = false

        try {
            while (attempts < maxAttempts) {
                val currentAttempt = attempts + 1
                Log.d(TAG, "Polling attempt $currentAttempt/$maxAttempts for taskId: $taskId")
                val response = VideoApiClient.apiService.queryVideoGenerationStatus(authorization, taskId)
                val responseBody = response.body()

                if (response.isSuccessful && responseBody != null) {
                    Log.d(TAG, "Poll response: Status=${responseBody.status}, FileId=${responseBody.fileId}, BaseMsg=${responseBody.baseResp.statusMsg}")
                    if (responseBody.status == "Success" && responseBody.baseResp.statusMsg == "success") {
                        if (!responseBody.fileId.isNullOrEmpty()) {
                            Log.d(TAG, "Polling successful. File ID: ${responseBody.fileId}. Retrieving video file.")
                            retrieveVideoFile(responseBody.fileId)
                            return
                        } else {
                            Log.e(TAG, "Polling status success, but file_id is missing. Response: ${responseBody.toString()}")
                            handleError("Polling successful, but file ID is missing.")
                            break
                        }
                    } else if (responseBody.status.equals("Processing", ignoreCase = true) || 
                               responseBody.status.equals("Pending", ignoreCase = true) || 
                               responseBody.status.equals("Preparing", ignoreCase = true) || 
                               responseBody.status.equals("Queueing", ignoreCase = true) ||
                               responseBody.baseResp.statusMsg.equals("processing", ignoreCase = true)) {
                        Log.d(TAG, "Video still processing/queueing. Status: ${responseBody.status}, BaseMsg: ${responseBody.baseResp.statusMsg}")
                    } else { 
                        Log.e(TAG, "Polling returned non-success/non-processing status: ${responseBody.status}, BaseMsg: ${responseBody.baseResp.statusMsg}. Full Response: ${responseBody.toString()}")
                        handleError("Video generation failed or encountered an error during polling: Status: ${responseBody.status}, Message: ${responseBody.baseResp.statusMsg}")
                        break
                    }
                } else { 
                    val errorBodyString = response.errorBody()?.string()
                    Log.e(TAG, "Error polling status: HTTP ${response.code()}, Body: $errorBodyString. Full Response: ${responseBody?.toString()}")
                    handleError("Error polling status (HTTP ${response.code()}): $errorBodyString")
                    break
                }
                attempts++
                if (attempts < maxAttempts) delay(5000)
            }
            if (attempts == maxAttempts && _isLoading.value) {
                 Log.w(TAG, "Video generation timed out after $maxAttempts attempts for taskId: $taskId.")
                handleError("Video generation timed out.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network error during polling loop for taskId: $taskId: ${e.message}", e)
            handleError("Network error (polling): ${e.message}")
        } finally {
            if (_isLoading.value && !success) { 
                stopTimerAndSetTotalTime(success)
            }
        }
    }

    private fun retrieveVideoFile(fileId: String) {
        Log.d(TAG, "retrieveVideoFile called for fileId: $fileId")
        var success = false
        viewModelScope.launch {
            try {
                val authorization = "Bearer ${VideoGenApiKey.API_KEY}"
                val groupId = VideoGenApiKey.GROUP_ID
                Log.d(TAG, "Retrieving video with groupId: $groupId, fileId: $fileId")
                
                val response = VideoApiClient.apiService.retrieveVideoFile(authorization, groupId, fileId)
                val responseBody = response.body()

                if (response.isSuccessful && responseBody?.file != null && responseBody.baseResp.statusMsg == "success") {
                    val fileData = responseBody.file
                    var rawUrl = fileData.downloadUrl
                    Log.d(TAG, "Video file retrieved. Raw Primary URL: ${rawUrl}, Raw Backup URL: ${fileData.backupDownloadUrl}")

                    if (rawUrl.isNullOrEmpty()) {
                        Log.w(TAG, "Primary downloadUrl is null or empty. Trying backupDownloadUrl.")
                        rawUrl = fileData.backupDownloadUrl
                    }

                    if (!rawUrl.isNullOrEmpty()) {
                        var decodedUrl = Uri.decode(rawUrl)
                        
                        if (!decodedUrl.startsWith("http")) {
                            decodedUrl = "https://$decodedUrl"
                            Log.d(TAG, "Added https:// prefix to URL")
                        }
                        
                        if (decodedUrl.contains("?")) {
                            val urlParts = decodedUrl.split("?")
                            if (urlParts.isNotEmpty()) {
                                decodedUrl = urlParts[0]
                                Log.d(TAG, "Removed query parameters from URL")
                            }
                        }
                        
                        Log.i(TAG, "Final processed video URL: $decodedUrl")
                        _videoUrl.value = decodedUrl
                        _error.value = null 
                        success = true
                    } else {
                        Log.e(TAG, "Both downloadUrl and backupDownloadUrl are null or empty. Response: ${responseBody.toString()}")
                        _error.value = "Failed to get a valid video download URL from API."
                    }
                } else {
                    val errorBodyString = response.errorBody()?.string()
                    val baseRespMsg = responseBody?.baseResp?.statusMsg
                    Log.e(TAG, "Error retrieving video file: HTTP ${response.code()}, BaseRespMsg: $baseRespMsg, ErrorBody: $errorBodyString. Full Response: ${responseBody?.toString()}")
                    _error.value = "Error retrieving video file (HTTP ${response.code()}): ${baseRespMsg ?: errorBodyString ?: "Unknown error"}"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error retrieving file: ${e.message}", e)
                _error.value = "Network error (retrieveVideoFile): ${e.message}"
            } finally {
                stopTimerAndSetTotalTime(success)
            }
        }
    }
}


