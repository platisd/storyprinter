package com.example.storyprinter.story;

import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory story session that lasts for the lifetime of the app process.
 *
 * Not persisted to disk: it survives activity recreation/navigation but not process death.
 */
public final class StorySession {

    public static final class Page {
        public final int pageNumber;
        public String text;
        public Bitmap image;

        public Page(int pageNumber) {
            this.pageNumber = pageNumber;
        }
    }

    private static final StorySession INSTANCE = new StorySession();

    public static StorySession get() {
        return INSTANCE;
    }

    private final List<Page> pages = new ArrayList<>();

    /** For Responses API chaining for text calls. */
    private String previousTextResponseId;

    /** For Responses API chaining for image calls (only image-to-image chaining). */
    private String previousImageResponseId;

    /** The seed prompt last used to start the story (optional). */
    private String seedPrompt;

    /** Optional user-provided reference image (base64, without data-url prefix). */
    private String referenceImageBase64;

    /** Small thumbnail for UI (kept in-memory only). */
    private Bitmap referenceImageThumbnail;

    private StorySession() {
    }

    public synchronized void clear() {
        clear(true);
    }

    public synchronized void clear(boolean removeReferenceImage) {
        pages.clear();
        previousTextResponseId = null;
        previousImageResponseId = null;
        seedPrompt = null;
        if (removeReferenceImage) {
            referenceImageBase64 = null;
            referenceImageThumbnail = null;
        }
    }

    public synchronized List<Page> snapshotPages() {
        return List.copyOf(pages);
    }

    public synchronized Page addNewPage(int pageNumber) {
        Page p = new Page(pageNumber);
        pages.add(p);
        return p;
    }

    public synchronized int getNextPageNumber() {
        return pages.size() + 1;
    }

    public synchronized boolean hasStarted() {
        return !pages.isEmpty() || (seedPrompt != null && !seedPrompt.trim().isEmpty());
    }

    public synchronized String getPreviousTextResponseId() {
        return previousTextResponseId;
    }

    public synchronized void setPreviousTextResponseId(String id) {
        this.previousTextResponseId = id;
    }

    public synchronized String getPreviousImageResponseId() {
        return previousImageResponseId;
    }

    public synchronized void setPreviousImageResponseId(String id) {
        this.previousImageResponseId = id;
    }

    public synchronized String getSeedPrompt() {
        return seedPrompt;
    }

    public synchronized void setSeedPrompt(String seedPrompt) {
        this.seedPrompt = seedPrompt;
    }

    public synchronized String getReferenceImageBase64() {
        return referenceImageBase64;
    }

    public synchronized Bitmap getReferenceImageThumbnail() {
        return referenceImageThumbnail;
    }

    public synchronized void setReferenceImage(String base64, Bitmap thumbnail) {
        this.referenceImageBase64 = base64;
        this.referenceImageThumbnail = thumbnail;
    }

    public synchronized void clearReferenceImage() {
        this.referenceImageBase64 = null;
        this.referenceImageThumbnail = null;
    }
}
