package com.topjohnwu.magisk.ui.theme

import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.doOnNextLayout
import com.topjohnwu.magisk.BR
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.arch.BaseFragment
import com.topjohnwu.magisk.arch.viewModel
import com.topjohnwu.magisk.core.Config
import com.topjohnwu.magisk.databinding.FragmentThemeMd2Binding
import com.topjohnwu.magisk.databinding.ItemThemeBindingImpl
import com.topjohnwu.magisk.core.R as CoreR

class ThemeFragment : BaseFragment<FragmentThemeMd2Binding>(), ThemeClickListener {

    override val layoutRes = R.layout.fragment_theme_md2
    override val viewModel by viewModel<ThemeViewModel>()

    private fun <T> Array<T>.paired(): List<Pair<T, T?>> {
        val iterator = iterator()
        if (!iterator.hasNext()) return emptyList()
        val result = mutableListOf<Pair<T, T?>>()
        while (iterator.hasNext()) {
            val a = iterator.next()
            val b = if (iterator.hasNext()) iterator.next() else null
            result.add(a to b)
        }
        return result
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        super.onCreateView(inflater, container, savedInstanceState)

        // The previews are inflated after the first frame is drawn, so the
        // screen appears immediately instead of waiting on all nine themes.
        binding.root.post {
            val activity = activity ?: return@post

            for ((a, b) in Theme.values().paired()) {
                val c = inflater.inflate(R.layout.item_theme_container, null, false)
                val left = c.findViewById<FrameLayout>(R.id.left)
                val right = c.findViewById<FrameLayout>(R.id.right)

                for ((theme, view) in listOf(a to left, b to right)) {
                    theme ?: continue
                    val themed = ContextThemeWrapper(activity, theme.themeRes)
                    ItemThemeBindingImpl.inflate(LayoutInflater.from(themed), view, true).also {
                        it.setVariable(BR.listener, this)
                        it.setVariable(BR.theme, theme)
                        it.lifecycleOwner = viewLifecycleOwner
                    }
                }

                // A lone theme in the last row spans the full width.
                if (b == null) right.visibility = View.GONE

                binding.themeContainer.addView(c)
            }

            // Restore the scroll position once the previews are laid out and
            // the container knows its full height.
            binding.themeScroll.doOnNextLayout {
                it.scrollTo(0, viewModel.savedScrollY)
            }
        }

        return binding.root
    }

    override fun onThemeClick(theme: Theme) {
        if (theme.isSelected) return
        viewModel.savedScrollY = binding.themeScroll.scrollY
        Config.themeOrdinal = theme.ordinal
        activity?.recreate()
    }

    override fun onStart() {
        super.onStart()

        activity?.title = getString(CoreR.string.section_theme)
    }

}
