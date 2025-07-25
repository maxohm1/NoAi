package max.ohm.oneai.imagetoimage.viewmodel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import max.ohm.oneai.imagetoimage.repository.ImageToImageRepository
import max.ohm.oneai.imagetoimage.repository.SimpleImageUploadService
import max.ohm.oneai.stabilityai.repository.StabilityRepository
import max.ohm.oneai.stabilityai.data.StabilityImageResponse
import java.io.ByteArrayOutputStream
import java.io.File

data class ImageToImageUiState(
    val imageUrl: String = "",
    val prompt: String = "A galactic dog in space",
    val imageUri: Uri? = null,
    val isLoading: Boolean = false,
    val generatedImageUrl: String? = null,
    val errorMessage: String? = null,
    val useStabilityAI: Boolean = true,  // Default to Stability AI
    val isSearchAndReplaceMode: Boolean = false,
    val searchPrompt: String = "dog",
    val replacePrompt: String = "golden retriever"
)

class ImageToImageViewModel : ViewModel() {
    
    private val _uiState = MutableStateFlow(ImageToImageUiState())
    val uiState: StateFlow<ImageToImageUiState> = _uiState.asStateFlow()
    
    private var context: Context? = null
    
    fun setContext(context: Context) {
        this.context = context
    }
    
    fun updateImageUrl(url: String) {
        _uiState.value = _uiState.value.copy(imageUrl = url, imageUri = null, errorMessage = null)
    }
    
    fun updatePrompt(prompt: String) {
        _uiState.value = _uiState.value.copy(prompt = prompt)
    }
    
    fun updateImageUri(uri: android.net.Uri?) {
        _uiState.value = _uiState.value.copy(imageUri = uri, imageUrl = "", errorMessage = null)
    }
    
    fun toggleProvider() {
        _uiState.value = _uiState.value.copy(
            useStabilityAI = !_uiState.value.useStabilityAI,
            errorMessage = null
        )
    }
    
    fun toggleSearchAndReplaceMode() {
        _uiState.value = _uiState.value.copy(
            isSearchAndReplaceMode = !_uiState.value.isSearchAndReplaceMode,
            errorMessage = null
        )
    }
    
    fun updateSearchPrompt(prompt: String) {
        _uiState.value = _uiState.value.copy(searchPrompt = prompt)
    }
    
    fun updateReplacePrompt(prompt: String) {
        _uiState.value = _uiState.value.copy(replacePrompt = prompt)
    }
    
    fun generateImage() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                errorMessage = null,
                generatedImageUrl = null
            )
            
            try {
                if (_uiState.value.useStabilityAI) {
                    // Use Stability AI
                    if (_uiState.value.imageUri == null) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            errorMessage = "Please select an image to use Stability AI"
                        )
                        return@launch
                    }
                    
                    if (_uiState.value.isSearchAndReplaceMode) {
                        // Use search and replace API
                        val response = StabilityRepository.searchAndReplace(
                            context!!,
                            _uiState.value.imageUri!!,
                            _uiState.value.replacePrompt,
                            _uiState.value.searchPrompt,
                            "webp"
                        )
                        
                        if (response?.status == "success" && response.imageData != null) {
                            val generatedFile = File.createTempFile("search_replace", ".webp", context!!.cacheDir)
                            generatedFile.writeBytes(response.imageData)
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                generatedImageUrl = generatedFile.toURI().toString()
                            )
                        } else {
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                errorMessage = response?.error ?: "Failed to search and replace"
                            )
                        }
                    } else {
                        // Use regular image-to-image API
                        val response = StabilityRepository.generateImageToImage(
                            context!!,
                            _uiState.value.imageUri!!,
                            _uiState.value.prompt
                        )
                        
                        if (response?.status == "success" && response.imageData != null) {
                            val generatedFile = File.createTempFile("generated", ".png", context!!.cacheDir)
                            generatedFile.writeBytes(response.imageData)
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                generatedImageUrl = generatedFile.toURI().toString()
                            )
                        } else {
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                errorMessage = response?.error ?: "Failed to generate image"
                            )
                        }
                    }
                } else {
                    // Use existing ModelsLab API
                    val imageUrl = when {
                        _uiState.value.imageUrl.isNotBlank() -> _uiState.value.imageUrl
                        _uiState.value.imageUri != null -> {
                            // Try to upload the image first
                            context?.let { ctx ->
                                val uploadedUrl = SimpleImageUploadService.uploadImageFromUri(ctx, _uiState.value.imageUri!!) ?: null
                                if (uploadedUrl != null) {
                                    uploadedUrl
                                } else {
                                    _uiState.value = _uiState.value.copy(
                                        isLoading = false,
                                        errorMessage = "Failed to upload image. Please make sure that the image service is configured properly."
                                    )
                                    return@launch
                                }
                            } ?: run {
                                _uiState.value = _uiState.value.copy(
                                    isLoading = false,
                                    errorMessage = "Context not available. Please restart the app."
                                )
                                return@launch
                            }
                        }
                        else -> {
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                errorMessage = "Please enter an image URL or select an image"
                            )
                            return@launch
                        }
                    }
                    
                    val response = ImageToImageRepository.generateImage(
                        initImageUrl = imageUrl,
                        prompt = _uiState.value.prompt
                    )
                    
                    when {
                        response.status == "success" && !response.output.isNullOrEmpty() -> {
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                generatedImageUrl = response.output.first()
                            )
                        }
                        response.status == "processing" -> {
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                errorMessage = "Image is still processing. Please try again in a few seconds."
                            )
                        }
                        response.error != null -> {
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                errorMessage = "API Error: ${response.error}"
                            )
                        }
                        response.messege != null -> {  // Handle API typo
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                errorMessage = "API Message: ${response.messege}"
                            )
                        }
                        else -> {
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                errorMessage = "Failed to generate image: ${response.message ?: response.status ?: "Unknown error"}"
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Network error: ${e.message}"
                )
            }
        }
    }
}
