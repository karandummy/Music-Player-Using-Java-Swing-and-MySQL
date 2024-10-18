import javazoom.jl.decoder.JavaLayerException;
import javazoom.jl.player.Player;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.sql.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.filechooser.FileNameExtensionFilter;


public class MusicApp extends JFrame {
    private DefaultListModel<String> playlistModel;
    private DefaultListModel<String> songModel;
    private JList<String> playlistList;
    private JList<String> songList;
    private JButton addPlaylistButton, removePlaylistButton, addSongButton, removeSongButton, playButton, togglePauseButton, stopButton;
    private JLabel albumArtLabel;
    private Player mp3Player;
    private Thread playerThread;
    private boolean isPlaying = false;
    private boolean isPaused = false;
    private String currentSongTitle;
    private InputStream currentSongInputStream;

    private AtomicBoolean stopFlag = new AtomicBoolean(false);

    private final String DB_URL = "jdbc:mysql://localhost:3306/songs";
    private final String USER = ""; // your username for MySQl
    private final String PASSWORD = ""; // your password for MySQl

    public MusicApp() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(this, "Error loading MySQL Driver: " + e.getMessage());
        }

        setTitle("Playlist Manager");
        setSize(1000, 400);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        albumArtLabel = new JLabel();
        albumArtLabel.setHorizontalAlignment(JLabel.CENTER);
        albumArtLabel.setPreferredSize(new Dimension(300, 300)); // Adjust size as needed
        add(albumArtLabel, BorderLayout.EAST);

        JPanel playlistPanel = new JPanel();
        playlistPanel.setLayout(new BorderLayout());

        playlistModel = new DefaultListModel<>();
        playlistList = new JList<>(playlistModel);
        playlistList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        playlistList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                String selectedPlaylist = playlistList.getSelectedValue();
                loadSongsForPlaylist(selectedPlaylist);
            }
        });

        JScrollPane playlistScroll = new JScrollPane(playlistList);
        playlistPanel.add(playlistScroll, BorderLayout.CENTER);

        JPanel playlistButtonPanel = new JPanel();
        addPlaylistButton = new JButton("Add Playlist");
        removePlaylistButton = new JButton("Remove Playlist");

        addPlaylistButton.addActionListener(e -> addPlaylist());
        removePlaylistButton.addActionListener(e -> removePlaylist());

        playlistButtonPanel.add(addPlaylistButton);
        playlistButtonPanel.add(removePlaylistButton);

        playlistPanel.add(playlistButtonPanel, BorderLayout.SOUTH);
        add(playlistPanel, BorderLayout.WEST);

        JPanel songPanel = new JPanel();
        songPanel.setLayout(new BorderLayout());

        songModel = new DefaultListModel<>();
        songList = new JList<>(songModel);
        songList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JScrollPane songScroll = new JScrollPane(songList);
        songPanel.add(songScroll, BorderLayout.CENTER);

        JPanel songButtonPanel = new JPanel();
        addSongButton = new JButton("Add Song");
        removeSongButton = new JButton("Remove Song");
        playButton = new JButton("Play");
        stopButton = new JButton("Stop");

        addSongButton.addActionListener(e -> addSong());
        removeSongButton.addActionListener(e -> removeSong());
        playButton.addActionListener(e -> playSong());
        stopButton.addActionListener(e -> stopSong());

        songButtonPanel.add(addSongButton);
        songButtonPanel.add(removeSongButton);
        songButtonPanel.add(playButton);
        songButtonPanel.add(stopButton);

        songPanel.add(songButtonPanel, BorderLayout.SOUTH);
        add(songPanel, BorderLayout.CENTER);

        loadPlaylists();
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(DB_URL, USER, PASSWORD);
    }

    private void loadPlaylists() {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT name FROM Playlists")) {

            while (rs.next()) {
                playlistModel.addElement(rs.getString("name"));
            }
        } catch (SQLException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(this, "Error loading playlists: " + e.getMessage());
        }
    }

    private void loadSongsForPlaylist(String playlistName) {
        songModel.clear();
        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT Songs.title FROM PlaylistSongs " +
                             "JOIN Songs ON PlaylistSongs.song_id = Songs.song_id " +
                             "JOIN Playlists ON PlaylistSongs.playlist_id = Playlists.playlist_id " +
                             "WHERE Playlists.name = ?")) {
            stmt.setString(1, playlistName);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                String songTitle = rs.getString("title");
                songModel.addElement(songTitle);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(this, "Error loading songs: " + e.getMessage());
        }
    }

    private void addPlaylist() {
        String playlistName = JOptionPane.showInputDialog(this, "Enter Playlist Name:");
        if (playlistName != null && !playlistName.isEmpty()) {
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement("INSERT INTO Playlists (name) VALUES (?)")) {
                stmt.setString(1, playlistName);
                stmt.executeUpdate();
                playlistModel.addElement(playlistName);
            } catch (SQLException e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(this, "Error adding playlist: " + e.getMessage());
            }
        }
    }

    private void removePlaylist() {
        int selectedIndex = playlistList.getSelectedIndex();
        if (selectedIndex != -1) {
            String playlistName = playlistModel.get(selectedIndex);
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement("DELETE FROM Playlists WHERE name = ?")) {
                stmt.setString(1, playlistName);
                stmt.executeUpdate();
                playlistModel.remove(selectedIndex);
                songModel.clear(); // Clear songs when playlist is removed
            } catch (SQLException e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(this, "Error removing playlist: " + e.getMessage());
            }
        }
    }

   private void addSong() {
    JFileChooser fileChooser = new JFileChooser();
    fileChooser.setDialogTitle("Select a Song File");

    // Create a filter for MP3 files
    FileNameExtensionFilter filter = new FileNameExtensionFilter("MP3 Files", "mp3");
    fileChooser.setFileFilter(filter);

    int userSelection = fileChooser.showOpenDialog(this);

    if (userSelection == JFileChooser.APPROVE_OPTION) {
        File songFile = fileChooser.getSelectedFile();
        String title = songFile.getName();

        // Add another JFileChooser for album art selection
        JFileChooser albumArtChooser = new JFileChooser();
        albumArtChooser.setDialogTitle("Select Album Art File");
        FileNameExtensionFilter albumArtFilter = new FileNameExtensionFilter("Image Files", "jpg", "jpeg", "png","jfif");
        albumArtChooser.setFileFilter(albumArtFilter);


        File albumArtFile = null; // Initialize albumArtFile variable
        int albumArtSelection = albumArtChooser.showOpenDialog(this);
        if (albumArtSelection == JFileChooser.APPROVE_OPTION) {
            albumArtFile = albumArtChooser.getSelectedFile();
        }

        try (Connection conn = getConnection();
             PreparedStatement stmt = conn.prepareStatement("INSERT INTO Songs (title, song_file, album_art) VALUES (?, ?, ?)")) {

            stmt.setString(1, title);
            stmt.setBlob(2, new FileInputStream(songFile)); // Store the song file

            if (albumArtFile != null) {
                stmt.setBlob(3, new FileInputStream(albumArtFile)); // Store the album art
                System.out.println("Album art stored: " + albumArtFile.getName());
            } else {
                stmt.setNull(3, java.sql.Types.BLOB); // Set to null if no album art is being added
                System.out.println("No album art provided.");
            }

            stmt.executeUpdate(); // Execute the insert
            songModel.addElement(title); // Update the song model

            // Add the song to the selected playlist
            String selectedPlaylist = playlistList.getSelectedValue();
            if (selectedPlaylist != null) {
                int songId;
                try (PreparedStatement songIdStmt = conn.prepareStatement("SELECT song_id FROM Songs WHERE title = ?")) {
                    songIdStmt.setString(1, title);
                    ResultSet rs = songIdStmt.executeQuery();
                    if (rs.next()) {
                        songId = rs.getInt("song_id");

                        try (PreparedStatement mappingStmt = conn.prepareStatement("INSERT INTO PlaylistSongs (playlist_id, song_id ) VALUES ((SELECT playlist_id FROM Playlists WHERE name = ?), ?)")) {
                            mappingStmt.setString(1, selectedPlaylist);
                            mappingStmt.setInt(2, songId);
                          
                            mappingStmt.executeUpdate();
                        }
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(this, "Database error adding song: " + e.getMessage());
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(this, "File not found: " + e.getMessage());
        }
    }
}

    private void removeSong() {
        int selectedIndex = songList.getSelectedIndex();
        if (selectedIndex != -1) {
            String songTitle = songModel.get(selectedIndex);
            try (Connection conn = getConnection()) {
                if (isPlaying && songTitle.equals(currentSongTitle)) {
                    stopSong(); // Stop the current song if it's the one being removed
                }

                int songId;
                try (PreparedStatement getIdStmt = conn.prepareStatement("SELECT song_id FROM Songs WHERE title = ?")) {
                    getIdStmt.setString(1, songTitle);
                    ResultSet rs = getIdStmt.executeQuery();
                    if (rs.next()) {
                        songId = rs.getInt("song_id");

                        try (PreparedStatement deleteMappingStmt = conn.prepareStatement("DELETE FROM PlaylistSongs WHERE song_id = ?")) {
                            deleteMappingStmt.setInt(1, songId);
                            deleteMappingStmt.executeUpdate();
                        }

                        try (PreparedStatement deleteSongStmt = conn.prepareStatement("DELETE FROM Songs WHERE song_id = ?")) {
                            deleteSongStmt.setInt(1, songId);
                            deleteSongStmt.executeUpdate();
                        }

                        songModel.remove(selectedIndex);
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(this, "Error removing song: " + e.getMessage());
            }
        }
    }


      private void displayImageFromByteArray(byte[] imageData, JLabel label) {
         ImageIcon icon = new ImageIcon("gimme more.jpg");
    if (imageData != null && imageData.length > 0) {
        try {
            // Convert byte array to BufferedImage
           
            BufferedImage albumArtImage = ImageIO.read(new ByteArrayInputStream(imageData));
            if (albumArtImage != null) {
                // Resize the image if necessary
                Image scaledImage = albumArtImage.getScaledInstance(200, 200, Image.SCALE_SMOOTH); // Change dimensions as needed
                label.setIcon(new ImageIcon(scaledImage));
                System.out.println("Image loaded successfully.");
            } else {
                System.out.println("Image is null after reading.");
                label.setIcon(null); // Clear if no image
            }
        } catch (IOException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(label, "Error loading image: " + e.getMessage());
            label.setIcon(null); // Clear icon on error
        }
    } else {
        System.out.println("Byte array is null or empty.");
        label.setIcon(icon); // Clear if no image
    }
}




private void playSong() {
    int selectedIndex = songList.getSelectedIndex();
    if (selectedIndex != -1) {
        String songTitle = songModel.get(selectedIndex);
        try {
            // Stop the currently playing song if there's one
            if (isPlaying) {
                stopSong();
            }

            // Get the song file and album art from the database
            try (Connection conn = getConnection();
                 PreparedStatement stmt = conn.prepareStatement("SELECT song_file, album_art FROM Songs WHERE title = ?")) {
                stmt.setString(1, songTitle);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    currentSongInputStream = rs.getBinaryStream("song_file");
                    currentSongTitle = songTitle;

                    // Load and display album art using the new function
                    byte[] albumArtData = rs.getBytes("album_art");
                    System.out.print(albumArtData);
                    if (albumArtData != null) {
                        System.out.println("Album art data retrieved successfully, size: " + albumArtData.length);
                    } else {
                        System.out.println("Album art data is null or empty.");
                    }
                    displayImageFromByteArray(albumArtData, albumArtLabel);

                    // Create a new Player instance
                    try {
                        mp3Player = new Player(currentSongInputStream);
                        isPlaying = true;
                        isPaused = false; // Reset pause state
                        stopFlag.set(false);

                        // Create a new thread to play the song
                        playerThread = new Thread(() -> {
                            try {
                                mp3Player.play();
                            } catch (JavaLayerException e) {
                                e.printStackTrace();
                                JOptionPane.showMessageDialog(this, "Error playing song: " + e.getMessage());
                            } finally {
                                isPlaying = false;
                                currentSongInputStream = null; // Clear input stream
                                currentSongTitle = null; // Clear current song title
                                albumArtLabel.setIcon(null); // Clear album art when done
                            }
                        });
                        playerThread.start();
                    } catch (JavaLayerException e) {
                        e.printStackTrace();
                        JOptionPane.showMessageDialog(this, "Error initializing player: " + e.getMessage());
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(this, "Error loading song: " + e.getMessage());
        }
    }
}




    private void stopSong() {
        if (isPlaying) {
            stopFlag.set(true);
            mp3Player.close();
            isPlaying = false;
            currentSongInputStream = null; // Clear input stream
            currentSongTitle = null; // Clear current song title
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            MusicApp app = new MusicApp();
            app.setVisible(true);
        });
    }
}
