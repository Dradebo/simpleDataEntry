package com.ash.simpledataentry.data.repositoryImpl

import com.ash.simpledataentry.data.DatabaseProvider
import com.ash.simpledataentry.data.local.CachedUrlEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LoginUrlCacheRepository @Inject constructor(
    private val databaseProvider: DatabaseProvider
) {
    private val cachedUrlDao get() = databaseProvider.getSharedDatabase().cachedUrlDao()
    
    // URL validation pattern for DHIS2 server URLs
    private val urlPattern = Pattern.compile(
        "^(https?://)([\\da-z\\.-]+)\\.([a-z\\.]{2,6})([/\\w \\.-]*)*/?$",
        Pattern.CASE_INSENSITIVE
    )
    
    suspend fun getCachedUrls(): List<CachedUrlEntity> = withContext(Dispatchers.IO) {
        cachedUrlDao.getCachedUrls()
    }
    
    suspend fun addOrUpdateUrl(url: String) = withContext(Dispatchers.IO) {
        val cleanUrl = cleanUrl(url)
        if (isValidUrl(cleanUrl)) {
            val existingUrl = cachedUrlDao.getCachedUrl(cleanUrl)
            val currentTime = System.currentTimeMillis()
            
            if (existingUrl != null) {
                // Update existing URL usage
                cachedUrlDao.incrementUrlUsage(cleanUrl, currentTime)
            } else {
                // Insert new URL
                val newCachedUrl = CachedUrlEntity(
                    url = cleanUrl,
                    lastUsed = currentTime,
                    frequency = 1,
                    isValid = true
                )
                cachedUrlDao.insertCachedUrl(newCachedUrl)
            }
            
            // Cleanup old URLs to keep only top 20
            cachedUrlDao.cleanupOldUrls()
        }
    }
    
    suspend fun removeUrl(url: String) = withContext(Dispatchers.IO) {
        cachedUrlDao.deleteCachedUrl(url)
    }
    
    suspend fun cleanupInvalidUrls() = withContext(Dispatchers.IO) {
        cachedUrlDao.deleteInvalidUrls()
    }
    
    private fun cleanUrl(url: String): String {
        var cleaned = url.trim()
        
        // Add https:// if no protocol is specified
        if (!cleaned.startsWith("http://") && !cleaned.startsWith("https://")) {
            cleaned = "https://$cleaned"
        }
        
        // Remove trailing slash
        if (cleaned.endsWith("/")) {
            cleaned = cleaned.dropLast(1)
        }
        
        return cleaned
    }
    
    private fun isValidUrl(url: String): Boolean {
        return urlPattern.matcher(url).matches() && url.isNotBlank()
    }
    
    suspend fun getSuggestedUrls(query: String): List<CachedUrlEntity> = withContext(Dispatchers.IO) {
        val allUrls = cachedUrlDao.getAllCachedUrls()
        if (query.isBlank()) {
            return@withContext allUrls.take(5)
        }
        
        // Filter URLs that contain the query string
        allUrls.filter { 
            it.url.contains(query, ignoreCase = true) 
        }.take(5)
    }
}
