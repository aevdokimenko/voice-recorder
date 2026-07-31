package org.fossify.voicerecorder.adapters

import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import androidx.viewpager.widget.PagerAdapter
import org.fossify.voicerecorder.R
import org.fossify.voicerecorder.activities.SimpleActivity
import org.fossify.voicerecorder.fragments.MyViewPagerFragment
import org.fossify.voicerecorder.fragments.RecordingsFragment
import org.fossify.voicerecorder.fragments.TrashFragment

class ViewPagerAdapter(
    private val activity: SimpleActivity
) : PagerAdapter() {

    private val fragments = SparseArray<MyViewPagerFragment>()

    override fun instantiateItem(container: ViewGroup, position: Int): Any {
        val layout = when (position) {
            0 -> R.layout.fragment_recordings
            1 -> R.layout.fragment_trash
            else -> throw IllegalArgumentException("Invalid position. Count = $count, requested position = $position")
        }

        val view = activity.layoutInflater.inflate(layout, container, false)
        container.addView(view)

        fragments.put(position, view as MyViewPagerFragment)
        return view
    }

    override fun destroyItem(container: ViewGroup, position: Int, item: Any) {
        container.removeView(item as View)
    }

    override fun getCount() = 2

    override fun isViewFromObject(view: View, item: Any) = view == item

    fun onResume() {
        for (i in 0 until fragments.size()) {
            fragments[i].onResume()
        }
    }

    fun onDestroy() {
        for (i in 0 until fragments.size()) {
            fragments[i].onDestroy()
        }
    }

    fun finishActMode() {
        (fragments[0] as? RecordingsFragment)?.finishActMode()
        (fragments[1] as? TrashFragment)?.finishActMode()
    }

    fun searchTextChanged(text: String) {
        (fragments[0] as? RecordingsFragment)?.onSearchTextChanged(text)
        (fragments[1] as? TrashFragment)?.onSearchTextChanged(text)
    }
}
