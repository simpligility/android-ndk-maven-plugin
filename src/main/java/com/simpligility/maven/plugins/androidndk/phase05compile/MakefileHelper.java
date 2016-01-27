package com.simpligility.maven.plugins.androidndk.phase05compile;

import com.simpligility.maven.plugins.androidndk.common.AndroidExtension;
import com.simpligility.maven.plugins.androidndk.common.ArtifactResolverHelper;
import com.simpligility.maven.plugins.androidndk.common.Const;
import com.simpligility.maven.plugins.androidndk.common.JarHelper;
import com.simpligility.maven.plugins.androidndk.common.MavenToPlexusLogAdapter;
import com.simpligility.maven.plugins.androidndk.common.NativeHelper;
import com.simpligility.maven.plugins.androidndk.common.UnpackedLibHelper;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Various helper methods for dealing with Android Native makefiles.
 *
 * @author Johan Lindquist
 */
public class MakefileHelper
{
    public static class MakefileRequest
    {
        Set<Artifact> artifacts;
        String defaultNDKArchitecture;
        boolean useHeaderArchives;
        boolean leaveTemporaryBuildArtifacts;
        String[] architectures;
    }


    private class LibraryDetails
    {
        Artifact artifact;
        Artifact harArtifact;

        String architecture;
        String localModule;
        File libraryPath;
        String localSrcFiles;
        String localModuleFileName;

        boolean useHeaderArchives;
        boolean leaveTemporaryBuildArtifacts;

        List<File> includeDirectories;
        MakefileResponse makefileResponse;
    }

    public static final String MAKEFILE_CAPTURE_FILE = "ANDROID_MAVEN_PLUGIN_LOCAL_C_INCLUDES_FILE";
    
    /**
     * Holder for the result of creating a makefile.  This in particular keep tracks of all directories created
     * for extracted header files.
     */
    public static class MakefileResponse
    {
        final boolean leaveTemporaryBuildArtifacts;
        final StringBuilder makeFile;
        final List<File> includeDirectories;
        private Set<String> staticLibraryList = new HashSet<String> (  );
        private Set<String> sharedLibraryList = new HashSet<String> (  );

        public MakefileResponse ( List<File> includeDirectories, StringBuilder makeFile, boolean leaveTemporaryBuildArtifacts )
        {
            this.includeDirectories = includeDirectories;
            this.makeFile = makeFile;
            this.leaveTemporaryBuildArtifacts = leaveTemporaryBuildArtifacts;
        }

        public List<File> getIncludeDirectories()
        {
            return includeDirectories;
        }

        public String getMakeFile()
        {
            return makeFile.toString ();
        }

        public boolean isLeaveTemporaryBuildArtifacts()
        {
            return leaveTemporaryBuildArtifacts;
        }

        public boolean hasStaticLibraryDepdendencies ()
        {
            return !staticLibraryList.isEmpty ();
        }

        public String getStaticLibraryList ()
        {
            StringBuilder sb = new StringBuilder (  );
            for ( String staticLibraryName : staticLibraryList )
            {
                sb.append ( staticLibraryName );
                sb.append ( " " );
            }
            return sb.toString ();
        }

        public void addStaticLibraryName( final String staticLibraryName )
        {
            staticLibraryList.add ( staticLibraryName );
        }

        public boolean hasSharedLibraryDepdendencies ()
        {
            return !sharedLibraryList.isEmpty ();
        }

        public String getSharedLibraryList ()
        {
            StringBuilder sb = new StringBuilder (  );
            for ( String sharedLibraryName : sharedLibraryList )
            {
                sb.append ( sharedLibraryName );
                sb.append ( " " );
            }
            return sb.toString ();
        }

        public void addSharedLibraryName( final String sharedLibraryName )
        {
            sharedLibraryList.add ( sharedLibraryName );
        }

    }

    private final MavenProject project;
    private final Log log;
    private final ArtifactResolverHelper artifactResolverHelper;
    private final ArtifactHandler harArtifactHandler;
    private final File unpackedApkLibsDirectory;
    private final File ndkBuildDirectory;

    /**
     * Initialize the MakefileHelper by storing the supplied parameters to local variables.
     * @param log                       Log to which to write log output.
     * @param artifactResolverHelper    ArtifactResolverHelper to use to resolve the artifacts.
     * @param harHandler                ArtifactHandler for har files.
     * @param unpackedApkLibsDirectory  Folder in which apklibs are unpacked.
     */
    public MakefileHelper( final MavenProject project, final Log log, final ArtifactResolverHelper artifactResolverHelper,
                           final ArtifactHandler harHandler, final File unpackedApkLibsDirectory, final File ndkBuildDirectory )
    {
        this.project = project;
        this.log = log;
        this.artifactResolverHelper = artifactResolverHelper;
        this.harArtifactHandler = harHandler;
        this.unpackedApkLibsDirectory = unpackedApkLibsDirectory;
        this.ndkBuildDirectory = ndkBuildDirectory;
    }
    
    /**
     * Cleans up all include directories created in the temp directory during the build.
     *
     * @param makefileResponse The holder produced by the
     * {@link MakefileHelper#createMakefileFromArtifacts(Set, String, String, boolean)}
     */
    public static void cleanupAfterBuild( final MakefileResponse makefileResponse )
    {
        if ( !makefileResponse.isLeaveTemporaryBuildArtifacts() )
        {
            if ( makefileResponse.getIncludeDirectories() != null )
            {
                for ( File file : makefileResponse.getIncludeDirectories() )
                {
                    try
                    {
                        FileUtils.deleteDirectory( file );
                    }
                    catch ( IOException e )
                    {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    /**
     * Creates an Android Makefile based on the specified set of static library dependency artifacts.
     *
     * @param artifacts         The list of (static library) dependency artifacts to create the Makefile from
     * @param useHeaderArchives If true, the Makefile should include a LOCAL_EXPORT_C_INCLUDES statement, pointing to
     *                          the location where the header archive was expanded
     * @return The created Makefile
     */
    public MakefileResponse createMakefileFromArtifacts ( MakefileRequest makefileRequest )
            throws IOException, MojoExecutionException
    {
        final List<File> includeDirectories = new ArrayList<File>();
        final StringBuilder makeFile = new StringBuilder( "# Generated by Android Maven Plugin\n" );

        final MakefileResponse makefileResponse = new MakefileResponse ( includeDirectories, makeFile, makefileRequest.leaveTemporaryBuildArtifacts );

        final Set<Artifact> artifacts = makefileRequest.artifacts;

        // Add now output - allows us to somewhat intelligently determine the include paths to use for the header
        // archive
        makeFile.append( "$(shell echo \"LOCAL_C_INCLUDES=$(LOCAL_C_INCLUDES)\" > $(" + MAKEFILE_CAPTURE_FILE + "))" );
        makeFile.append( '\n' );
        makeFile.append( "$(shell echo \"LOCAL_PATH=$(LOCAL_PATH)\" >> $(" + MAKEFILE_CAPTURE_FILE + "))" );
        makeFile.append( '\n' );
        makeFile.append( "$(shell echo \"LOCAL_MODULE=$(LOCAL_MODULE)\" >> $(" + MAKEFILE_CAPTURE_FILE + "))" );
        makeFile.append( '\n' );
        makeFile.append( "$(shell echo \"LOCAL_MODULE_FILENAME=$(LOCAL_MODULE_FILENAME)\" >> $("
                + MAKEFILE_CAPTURE_FILE + "))" );
        makeFile.append( '\n' );
        makeFile.append( "$(shell echo \"LOCAL_CFLAGS=$(LOCAL_CFLAGS)\" >> $(" + MAKEFILE_CAPTURE_FILE + "))" );
        makeFile.append( '\n' );
        makeFile.append( "$(shell echo \"LOCAL_SHARED_LIBRARIES=$(LOCAL_SHARED_LIBRARIES)\" >> $(" + MAKEFILE_CAPTURE_FILE + "))" );
        makeFile.append( '\n' );
        makeFile.append( "$(shell echo \"LOCAL_STATIC_LIBRARIES=$(LOCAL_STATIC_LIBRARIES)\" >> $(" + MAKEFILE_CAPTURE_FILE + "))" );
        makeFile.append( '\n' );
        makeFile.append( "$(shell echo \"LOCAL_EXPORT_C_INCLUDES=$(LOCAL_EXPORT_C_INCLUDES)\" >> $(" + MAKEFILE_CAPTURE_FILE + "))" );
        makeFile.append( '\n' );
        makeFile.append( "$(shell echo \"LOCAL_SRC_FILES=$(LOCAL_SRC_FILES)\" >> $(" + MAKEFILE_CAPTURE_FILE + "))" );
        makeFile.append( '\n' );

        if ( ! artifacts.isEmpty() )
        {
            for ( Artifact artifact : artifacts )
            {
                // If we are dealing with bundled artifacts or not (in an APKLIB or AAR for example)
                if ( !isLibraryBundle( artifact ) )
                {
                    final String architecture = NativeHelper.extractArchitectureFromArtifact ( artifact, makefileRequest.defaultNDKArchitecture );

                    final LibraryDetails libraryDetails = new LibraryDetails ();

                    libraryDetails.makefileResponse = makefileResponse;
                    libraryDetails.artifact = artifact;

                    libraryDetails.architecture = architecture;
                    libraryDetails.localModule = artifact.getArtifactId ();
                    libraryDetails.libraryPath = artifact.getFile ();

                    libraryDetails.useHeaderArchives = makefileRequest.useHeaderArchives;
                    libraryDetails.leaveTemporaryBuildArtifacts = makefileRequest.leaveTemporaryBuildArtifacts;

                    libraryDetails.includeDirectories = includeDirectories;

                    libraryDetails.harArtifact = new DefaultArtifact ( artifact.getGroupId (), artifact.getArtifactId (),
                                          artifact.getVersion (), artifact.getScope (),
                                          Const.ArtifactType.NATIVE_HEADER_ARCHIVE, artifact.getClassifier (), harArtifactHandler );

                    addLocalModule( libraryDetails );
                }
                else
                {
                    final LibraryDetails libraryDetails = new LibraryDetails ();

                    libraryDetails.makefileResponse = makefileResponse;
                    libraryDetails.artifact = artifact;
                    libraryDetails.useHeaderArchives = makefileRequest.useHeaderArchives;
                    libraryDetails.leaveTemporaryBuildArtifacts = makefileRequest.leaveTemporaryBuildArtifacts;

                    // Collect added include directorie
                    libraryDetails.includeDirectories = includeDirectories;


                    addLibraryBundleDetails ( libraryDetails, makefileRequest.architectures );
                }
            }
        }
        return makefileResponse;
    }


    private void addLocalModule ( final LibraryDetails libraryDetails ) throws MojoExecutionException, IOException
    {
        final Artifact artifact = libraryDetails.artifact;
        final StringBuilder makeFile = libraryDetails.makefileResponse.makeFile;
        final boolean isStaticLibrary = Const.ArtifactType.NATIVE_IMPLEMENTATION_ARCHIVE.equals ( libraryDetails.artifact.getType () );


        makeFile.append ( '\n' );
        makeFile.append ( "ifeq ($(TARGET_ARCH_ABI)," ).append ( libraryDetails.architecture ).append ( ")\n" );

        makeFile.append ( "#\n" );
        makeFile.append ( "# Group ID: " );
        makeFile.append ( artifact.getGroupId () );
        makeFile.append ( '\n' );
        makeFile.append ( "# Artifact ID: " );
        makeFile.append ( artifact.getArtifactId () );
        makeFile.append ( '\n' );
        makeFile.append ( "# Artifact Type: " );
        makeFile.append ( artifact.getType () );
        makeFile.append ( '\n' );
        makeFile.append ( "# Version: " );
        makeFile.append ( artifact.getVersion () );
        makeFile.append ( '\n' );
        makeFile.append ( "include $(CLEAR_VARS)" );
        makeFile.append ( '\n' );
        makeFile.append ( "LOCAL_MODULE    := " );
        makeFile.append ( libraryDetails.localModule );
        makeFile.append ( '\n' );

        if ( isStaticLibrary )
        {
            libraryDetails.makefileResponse.addStaticLibraryName ( libraryDetails.localModule );
        }
        else
        {
            libraryDetails.makefileResponse.addSharedLibraryName ( libraryDetails.localModule );
        }


        addLibraryDetails ( makeFile,  libraryDetails.libraryPath, artifact.getArtifactId() );

        if ( libraryDetails.useHeaderArchives )
        {
            try
            {
                final String classifier = artifact.getClassifier ();

                File resolvedHarArtifactFile = artifactResolverHelper.resolveArtifactToFile ( libraryDetails.harArtifact );
                log.debug ( "Resolved har artifact file : " + resolvedHarArtifactFile );

                final File includeDir = new File ( ndkBuildDirectory, "android_maven_plugin_native_includes" + System.currentTimeMillis () + "_"
                        + libraryDetails.harArtifact.getArtifactId () );

                if ( !libraryDetails.leaveTemporaryBuildArtifacts )
                {
                    includeDir.deleteOnExit ();
                }

                libraryDetails.includeDirectories.add ( includeDir );

                JarHelper.unjar ( new JarFile ( resolvedHarArtifactFile ), includeDir,
                        new JarHelper.UnjarListener ()
                        {
                            @Override
                            public boolean include ( JarEntry jarEntry )
                            {
                                return !jarEntry.getName ().startsWith ( "META-INF" );
                            }
                        } );

                makeFile.append ( "LOCAL_EXPORT_C_INCLUDES := " );
                makeFile.append ( includeDir.getAbsolutePath () );
                makeFile.append ( '\n' );

                if ( log.isDebugEnabled () )
                {
                    Collection<File> includes = FileUtils.listFiles ( includeDir, TrueFileFilter.INSTANCE, TrueFileFilter.INSTANCE );
                    log.debug ( "Listing LOCAL_EXPORT_C_INCLUDES for " + artifact.getId () + ": " + includes );
                }
            }
            catch ( RuntimeException e )
            {
                throw new MojoExecutionException ( "Error while resolving header archive file for: " + artifact.getArtifactId (), e );
            }
        }
        if ( isStaticLibrary )
        {
            makeFile.append ( "include $(PREBUILT_STATIC_LIBRARY)\n" );
        }
        else
        {
            makeFile.append ( "include $(PREBUILT_SHARED_LIBRARY)\n" );
        }

        makeFile.append ( "endif #" ).append ( artifact.getClassifier () ).append ( '\n' );
        makeFile.append ( '\n' );



    }

    private boolean isLibraryBundle( Artifact artifact )
    {
        return artifact.getType ().equals ( AndroidExtension.APKLIB ) || artifact.getType ().equals ( AndroidExtension.AAR );
    }

    private void addLibraryBundleDetails( final LibraryDetails libraryDetails, final String[] architectures ) throws MojoExecutionException, IOException
    {
        final Artifact artifact = libraryDetails.artifact;

        // At this point, we should peek at the files in the APK/AAR to see if
        // there are any native files.  If so, we should add those to the make file.
        // Also, from the list of architectures in the

        // So, extract the artifact, if need be
        UnpackedLibHelper unpackedLibHelper = new UnpackedLibHelper ( artifactResolverHelper, project, new MavenToPlexusLogAdapter ( log ), unpackedApkLibsDirectory );

        if ( artifact.getType ().equals ( AndroidExtension.AAR ) )
        {
            unpackedLibHelper.extractAarLib ( artifact );
        }
        else if ( artifact.getType ().equals ( AndroidExtension.APKLIB ) )
        {
            unpackedLibHelper.extractApklib ( artifact );
        }

        for ( int i = 0; i < architectures.length; i++ )
        {
            String architecture = architectures[ i ];

            final File[] staticLibs = NativeHelper.listNativeFiles ( artifact, unpackedLibHelper.getUnpackedLibNativesFolder ( artifact ), true, architecture );
            processBundledLibraries( architecture, libraryDetails, staticLibs );

            final File[] sharedLibs = NativeHelper.listNativeFiles ( artifact, unpackedLibHelper.getUnpackedLibNativesFolder ( artifact ), false, architecture );
            processBundledLibraries( architecture, libraryDetails, sharedLibs );

        }

    }

    private void processBundledLibraries ( final String architecture, final LibraryDetails libraryDetails, final File[] staticLibs ) throws IOException, MojoExecutionException
    {
        final Artifact artifact = libraryDetails.artifact;

        for ( File staticLib : staticLibs )
        {
            // For each static file, find the HAR artifca
            libraryDetails.architecture = architecture;
            libraryDetails.localModule = artifact.getArtifactId ();

            libraryDetails.libraryPath = staticLib;

            final String classifier = artifact.getClassifier () == null ? architecture : architecture + "-" + artifact.getClassifier ();

            libraryDetails.harArtifact = new DefaultArtifact ( artifact.getGroupId (), artifact.getArtifactId (),
                    artifact.getVersion (), artifact.getScope (),
                    Const.ArtifactType.NATIVE_HEADER_ARCHIVE, classifier, harArtifactHandler );

            libraryDetails.localModuleFileName = artifact.getArtifactId ();


            addLocalModule ( libraryDetails );

        }
    }

    private void addLibraryDetails( StringBuilder makeFile, File libFile, String outputName ) throws IOException
    {
        makeFile.append( "LOCAL_PATH := " );
        makeFile.append( libFile.getParentFile().getAbsolutePath() );
        makeFile.append( '\n' );
        makeFile.append( "LOCAL_SRC_FILES := " );
        makeFile.append( libFile.getName() );
        makeFile.append( '\n' );
        makeFile.append( "LOCAL_MODULE_FILENAME := " );
        if ( "".equals( outputName ) )
        {
            makeFile.append( FilenameUtils.removeExtension( libFile.getName() ) );
        }
        else
        {
            makeFile.append( outputName );
        }
        makeFile.append( '\n' );
    }

}
