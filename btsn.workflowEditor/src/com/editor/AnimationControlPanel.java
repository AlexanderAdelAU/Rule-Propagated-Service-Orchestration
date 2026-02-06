package com.editor;

import javax.swing.*;

import javax.swing.Timer;
import javax.swing.border.*;
import javax.swing.event.*;

import com.editor.TokenAnimator;
import com.editor.animator.*;

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;

/**
 * Control panel for token animation playback.
 * Provides play/pause, step, speed control, and timeline scrubber.
 * 
 * Updated to support buffer visualization at T_in transitions.
 */
public class AnimationControlPanel extends JPanel {
    
    // Remember last directory for analysis file loading
    private static File lastAnalysisDirectory = null;
    
    // Preferences for persisting last analysis directory across sessions
    private static final Preferences prefs = Preferences.userNodeForPackage(AnimationControlPanel.class);
    private static final String LAST_ANALYSIS_DIRECTORY_KEY = "lastAnalysisDirectory";
    
    private TokenAnimator animator;
    private Canvas canvas;
    
    // Playback state
    private boolean playing = false;
    private long currentTime = 0;
    private double playbackSpeed = 1.0;
    private Timer playbackTimer;
    
    // UI Components
    private JButton loadButton;
    private JButton playPauseButton;
    private JButton stepBackButton;
    private JButton stepForwardButton;
    private JButton resetButton;
    private JSlider timelineSlider;
    private JSlider speedSlider;
    private JLabel timeLabel;
    private JLabel speedLabel;
    private JLabel statusLabel;
    private JPanel legendPanel;
    
   
    // Listeners
    private List<AnimationListener> listeners = new ArrayList<>();
    
    // Pulse phase for token animation
    private float pulsePhase = 0;
    
    public interface AnimationListener {
        void onTimeChanged(long time, Map<String, String> tokenPositions);
        void onAnimationStarted();
        void onAnimationStopped();
    }
    
    public AnimationControlPanel(Canvas canvas) {
        this.canvas = canvas;
        this.animator = new TokenAnimator();
        
        // Wire the canvas to the animator for topology building
        this.animator.setCanvas(canvas);
        
        // Load last analysis directory from preferences
        loadLastAnalysisDirectory();
        
        setLayout(new BorderLayout(5, 5));
        setBorder(BorderFactory.createTitledBorder("Token Animation"));
        setPreferredSize(new Dimension(0, 140));
        
        initComponents();
        setupLayout();
        setupTimer();
    }
    
    /**
     * Load last analysis directory from preferences
     */
    private void loadLastAnalysisDirectory() {
        String lastDirPath = prefs.get(LAST_ANALYSIS_DIRECTORY_KEY, null);
        if (lastDirPath != null) {
            File dir = new File(lastDirPath);
            if (dir.exists() && dir.isDirectory()) {
                lastAnalysisDirectory = dir;
            }
        }
    }
    
    /**
     * Save last analysis directory to preferences
     */
    private void saveLastAnalysisDirectory(File directory) {
        if (directory != null && directory.isDirectory()) {
            lastAnalysisDirectory = directory;
            prefs.put(LAST_ANALYSIS_DIRECTORY_KEY, directory.getAbsolutePath());
        }
    }
    
    private void initComponents() {
        // Load button
        loadButton = new JButton("Load Analysis...");
        loadButton.setToolTipText("Load token analysis data from file");
        loadButton.addActionListener(e -> loadAnalysisData());
        
        // Playback controls
        playPauseButton = new JButton("Play");
        playPauseButton.setEnabled(false);
        playPauseButton.addActionListener(e -> togglePlayPause());
        
        stepBackButton = new JButton("|<");
        stepBackButton.setToolTipText("Previous event");
        stepBackButton.setEnabled(false);
        stepBackButton.setMargin(new Insets(2, 5, 2, 5));
        stepBackButton.addActionListener(e -> stepBackward());
        
        stepForwardButton = new JButton(">|");
        stepForwardButton.setToolTipText("Next event");
        stepForwardButton.setEnabled(false);
        stepForwardButton.setMargin(new Insets(2, 5, 2, 5));
        stepForwardButton.addActionListener(e -> stepForward());
        
        resetButton = new JButton("Reset");
        resetButton.setEnabled(false);
        resetButton.addActionListener(e -> resetAnimation());
        
        // Timeline slider
        timelineSlider = new JSlider(0, 1000, 0);
        timelineSlider.setEnabled(false);
        timelineSlider.addChangeListener(e -> {
            if (timelineSlider.getValueIsAdjusting()) {
                long time = sliderToTime(timelineSlider.getValue());
                setCurrentTimeFromSlider(time);
            }
        });
        
        // Speed slider - uses logarithmic scale for better control at low speeds
        // Slider 0-100: 0=0.1x, 50=1.0x, 100=10x
        speedSlider = new JSlider(0, 100, 50);  // Default 50 = 1.0x
        speedSlider.setPreferredSize(new Dimension(100, 20));
        speedSlider.addChangeListener(e -> {
            // Logarithmic scale: 10^((value - 50) / 50)
            // At 0: 10^-1 = 0.1x
            // At 50: 10^0 = 1.0x
            // At 100: 10^1 = 10x
            playbackSpeed = Math.pow(10, (speedSlider.getValue() - 50) / 50.0);
            speedLabel.setText(String.format("%.1fx", playbackSpeed));
        });
        
        // Labels
        timeLabel = new JLabel("Time: --");
        timeLabel.setFont(new Font("Monospaced", Font.PLAIN, 11));
        
        speedLabel = new JLabel("1.0x");
        speedLabel.setPreferredSize(new Dimension(35, 20));
        
        statusLabel = new JLabel("No data loaded");
        statusLabel.setForeground(Color.GRAY);
        
        // Legend panel
        legendPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        legendPanel.setOpaque(false);
    }
    
    private void setupLayout() {
        // Top row: Load button and status
        JPanel topPanel = new JPanel(new BorderLayout(10, 0));
        topPanel.add(loadButton, BorderLayout.WEST);
        topPanel.add(statusLabel, BorderLayout.CENTER);
        topPanel.add(legendPanel, BorderLayout.EAST);
        
        // Middle row: Timeline
        JPanel timelinePanel = new JPanel(new BorderLayout(5, 0));
        timelinePanel.add(timeLabel, BorderLayout.WEST);
        timelinePanel.add(timelineSlider, BorderLayout.CENTER);
        
        // Bottom row: Playback controls
        JPanel controlsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        controlsPanel.add(stepBackButton);
        controlsPanel.add(playPauseButton);
        controlsPanel.add(stepForwardButton);
        controlsPanel.add(Box.createHorizontalStrut(10));
        controlsPanel.add(resetButton);
        controlsPanel.add(Box.createHorizontalStrut(20));
        controlsPanel.add(new JLabel("Speed:"));
        controlsPanel.add(speedSlider);
        controlsPanel.add(speedLabel);
        
        // Assemble
        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.add(topPanel);
        mainPanel.add(Box.createVerticalStrut(5));
        mainPanel.add(timelinePanel);
        mainPanel.add(Box.createVerticalStrut(5));
        mainPanel.add(controlsPanel);
        
        add(mainPanel, BorderLayout.CENTER);
    }
    
    private void setupTimer() {
        // Timer fires every 33ms (~30 FPS) for smooth animation
        playbackTimer = new Timer(33, e -> {
            if (playing && animator.hasData()) {
                // Update pulse phase for token glow effect
                pulsePhase += 0.15f;
                if (pulsePhase > Math.PI * 2) pulsePhase = 0;
                canvas.setTokenPulsePhase(pulsePhase);
                
                // Advance time based on playback speed
                long deltaTime = (long)(33 * playbackSpeed);
                long newTime = currentTime + deltaTime;
                
                if (newTime >= animator.getEndTime()) {
                    newTime = animator.getEndTime();
                    stopPlayback();
                }
                
                setCurrentTime(newTime);
            }
        });
    }
    
    /**
     * Load analysis data from file
     */
    private void loadAnalysisData() {
        JFileChooser chooser = new JFileChooser();
        
        // Remember last directory
        if (lastAnalysisDirectory != null) {
            chooser.setCurrentDirectory(lastAnalysisDirectory);
        }
        
        chooser.setDialogTitle("Load Token Analysis Data");
        chooser.setFileFilter(new javax.swing.filechooser.FileFilter() {
            public boolean accept(File f) {
                return f.isDirectory() || f.getName().endsWith(".txt") || 
                       f.getName().endsWith(".log");
            }
            public String getDescription() {
                return "Analysis Output (*.txt, *.log)";
            }
        });
        
        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            File selectedFile = chooser.getSelectedFile();
            saveLastAnalysisDirectory(selectedFile.getParentFile());  // Persist to preferences
            loadAnalysisFile(selectedFile);
        }
    }
    
    /**
     * Load analysis data from a file
     */
    public void loadAnalysisFile(File file) {
        try {
            StringBuilder content = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
            }
            
            loadAnalysisText(content.toString());
            statusLabel.setText("Loaded: " + file.getName());
            
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this,
                "Error loading file: " + e.getMessage(),
                "Load Error",
                JOptionPane.ERROR_MESSAGE);
        }
    }
    
    /**
     * Load analysis data from text
     */
    public void loadAnalysisText(String text) {
        animator.clear();
        
        // Ensure topology is built from current canvas state
        animator.buildTopologyFromCanvas();
        
        // Parse the analysis output
        animator.parseAnalyzerOutput(text);
        
        if (animator.hasData()) {
            if (!animator.hasTopology()) {
                statusLabel.setText("Warning: Could not build topology from workflow");
                statusLabel.setForeground(new Color(200, 100, 0));
            } else {
                enableControls(true);
                updateLegend();
                resetAnimation();
                
                // Print topology for debugging
                animator.printTopology();
                
                statusLabel.setText(String.format("Loaded %d events, %d places, %d T_in, %.1fs duration",
                    animator.getEvents().size(),
                    animator.getPlaceIds().size(),
                    animator.getTInIds().size(),
                    animator.getDuration() / 1000.0));
                statusLabel.setForeground(new Color(0, 100, 0));
            }
        } else {
            statusLabel.setText("No token data found in file");
            statusLabel.setForeground(Color.RED);
        }
    }
    
    /**
     * Update the legend to show token colors
     */
    private void updateLegend() {
        legendPanel.removeAll();
        
        for (String version : animator.getVersions()) {
            Color color = animator.getVersionColor(version);
            
            JPanel colorBox = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    Graphics2D g2 = (Graphics2D) g;
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, 
                        RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(color);
                    g2.fillOval(2, 2, 12, 12);
                    g2.setColor(Color.BLACK);
                    g2.drawOval(2, 2, 12, 12);
                }
            };
            colorBox.setPreferredSize(new Dimension(16, 16));
            colorBox.setOpaque(false);
            
            legendPanel.add(colorBox);
            legendPanel.add(new JLabel(version));
            legendPanel.add(Box.createHorizontalStrut(5));
        }
        
        legendPanel.revalidate();
        legendPanel.repaint();
    }
    
    /**
     * Enable/disable playback controls
     */
    private void enableControls(boolean enabled) {
        playPauseButton.setEnabled(enabled);
        stepBackButton.setEnabled(enabled);
        stepForwardButton.setEnabled(enabled);
        resetButton.setEnabled(enabled);
        timelineSlider.setEnabled(enabled);
    }
    
    /**
     * Toggle play/pause
     */
    private void togglePlayPause() {
        if (playing) {
            stopPlayback();
        } else {
            startPlayback();
        }
    }
    
    /**
     * Start playback
     */
    public void startPlayback() {
        if (!animator.hasData()) return;
        
        playing = true;
        playPauseButton.setText("Pause");
        playbackTimer.start();
        
        for (AnimationListener l : listeners) {
            l.onAnimationStarted();
        }
    }
    
    /**
     * Stop playback
     */
    public void stopPlayback() {
        playing = false;
        playPauseButton.setText("Play");
        playbackTimer.stop();
        
        for (AnimationListener l : listeners) {
            l.onAnimationStopped();
        }
    }
    
    /**
     * Reset to beginning
     */
    public void resetAnimation() {
        stopPlayback();
        if (animator.hasData()) {
            setCurrentTime(animator.getStartTime());
        }
    }
    
    /**
     * Step to next event
     */
    private void stepForward() {
        if (!animator.hasData()) return;
        
        for (MarkingEvent event : animator.getEvents()) {
            if (event.timestamp > currentTime) {
                setCurrentTime(event.timestamp);
                return;
            }
        }
        
        setCurrentTime(animator.getEndTime());
    }
    
    /**
     * Step to previous event
     */
    private void stepBackward() {
        if (!animator.hasData()) return;
        
        MarkingEvent prevEvent = null;
        for (MarkingEvent event : animator.getEvents()) {
            if (event.timestamp >= currentTime) break;
            prevEvent = event;
        }
        
        if (prevEvent != null) {
            setCurrentTime(prevEvent.timestamp);
        } else {
            setCurrentTime(animator.getStartTime());
        }
    }
    
    /**
     * Set current animation time
     */
    public void setCurrentTime(long time) {
        if (!animator.hasData()) return;
        
        currentTime = Math.max(animator.getStartTime(), 
                      Math.min(time, animator.getEndTime()));
        
        int sliderValue = timeToSlider(currentTime);
        timelineSlider.setValue(sliderValue);
        
        updateDisplay();
    }
    
    /**
     * Set current time from slider (doesn't update slider to avoid loop)
     */
    private void setCurrentTimeFromSlider(long time) {
        if (!animator.hasData()) return;
        
        currentTime = Math.max(animator.getStartTime(), 
                      Math.min(time, animator.getEndTime()));
        
        updateDisplay();
    }
    
    /**
     * Update the display (time label, tokens, buffer states, listeners)
     */
    private void updateDisplay() {
        // Update time label
        long relativeTime = currentTime - animator.getStartTime();
        timeLabel.setText(String.format("Time: %d.%03ds", 
            relativeTime / 1000, relativeTime % 1000));
        
        // Get token animation states at this time
        Map<String, TokenAnimState> states = animator.getTokenStatesAt(currentTime);
        
        // Get buffer states at T_in transitions
        Map<String, List<BufferedToken>> bufferStates = animator.getBufferStatesAt(currentTime);
        
        // Build color map from states
        Map<String, Color> colors = new HashMap<>();
        for (Map.Entry<String, TokenAnimState> entry : states.entrySet()) {
            colors.put(entry.getKey(), animator.getVersionColor(entry.getValue().version));
        }
        
        // Push to canvas for drawing - use the combined method
        canvas.setAnimationState(states, colors, bufferStates);
        
        // Notify listeners
        Map<String, String> positions = new HashMap<>();
        for (Map.Entry<String, TokenAnimState> entry : states.entrySet()) {
            if (entry.getValue().currentPlaceId != null) {
                positions.put(entry.getKey(), entry.getValue().currentPlaceId);
            }
        }
        for (AnimationListener l : listeners) {
            l.onTimeChanged(currentTime, positions);
        }
    }
    
    /**
     * Convert slider value to time
     */
    private long sliderToTime(int value) {
        if (!animator.hasData()) return 0;
        double fraction = value / 1000.0;
        return animator.getStartTime() + 
               (long)(fraction * animator.getDuration());
    }
    
    /**
     * Convert time to slider value
     */
    private int timeToSlider(long time) {
        if (!animator.hasData() || animator.getDuration() == 0) return 0;
        double fraction = (double)(time - animator.getStartTime()) / 
                         animator.getDuration();
        return (int)(fraction * 1000);
    }
    
    /**
     * Add animation listener
     */
    public void addAnimationListener(AnimationListener listener) {
        listeners.add(listener);
    }
    
    /**
     * Remove animation listener
     */
    public void removeAnimationListener(AnimationListener listener) {
        listeners.remove(listener);
    }
    
    /**
     * Get the animator
     */
    public TokenAnimator getAnimator() {
        return animator;
    }
    
    /**
     * Get current time
     */
    public long getCurrentTime() {
        return currentTime;
    }
    
    /**
     * Check if animation is playing
     */
    public boolean isPlaying() {
        return playing;
    }
    
    
    /**
     * Rebuild topology from canvas (call after loading a new workflow)
     */
    public void rebuildTopology() {
        animator.buildTopologyFromCanvas();
        if (animator.hasTopology()) {
            System.out.println("Topology rebuilt successfully");
            animator.printTopology();
        }
    }
}