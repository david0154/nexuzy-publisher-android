package com.nexuzy.publisher.ui.main

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.nexuzy.publisher.data.model.WpCategory
import com.nexuzy.publisher.workflow.NewsWorkflowManager

class NewsViewModel : ViewModel() {

    private val _snapshot = MutableLiveData<NewsWorkflowManager.DailyNewsSnapshot>(
        NewsWorkflowManager.DailyNewsSnapshot()
    )
    val snapshot: LiveData<NewsWorkflowManager.DailyNewsSnapshot> = _snapshot

    private val _isFetching = MutableLiveData(false)
    val isFetching: LiveData<Boolean> = _isFetching

    /** Cached WordPress categories — fetched once from RssFragment, reused everywhere */
    private val _wpCategories = MutableLiveData<List<WpCategory>>(emptyList())
    val wpCategories: LiveData<List<WpCategory>> = _wpCategories

    fun setSnapshot(snap: NewsWorkflowManager.DailyNewsSnapshot) {
        _snapshot.postValue(snap)
    }

    fun setFetching(fetching: Boolean) {
        _isFetching.postValue(fetching)
    }

    fun setWpCategories(cats: List<WpCategory>) {
        _wpCategories.postValue(cats)
    }
}
