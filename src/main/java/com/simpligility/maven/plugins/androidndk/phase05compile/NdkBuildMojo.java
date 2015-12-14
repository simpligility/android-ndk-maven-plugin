/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.simpligility.maven.plugins.androidndk.phase05compile;

import com.simpligility.maven.plugins.androidndk.AndroidNdk;
import com.simpligility.maven.plugins.androidndk.CommandExecutor;
import com.simpligility.maven.plugins.androidndk.ExecutionException;
import com.simpligility.maven.plugins.androidndk.common.ArtifactResolverHelper;
import com.simpligility.maven.plugins.androidndk.common.Const;
import com.simpligility.maven.plugins.androidndk.common.MavenToPlexusLogAdapter;
import com.simpligility.maven.plugins.androidndk.common.NativeHelper;
import com.simpligility.maven.plugins.androidndk.configuration.AdditionallyBuiltModule;
import com.simpligility.maven.plugins.androidndk.configuration.HeaderFilesDirective;
import com.simpligility.maven.plugins.androidndk.configuration.ArchitectureToolchainMappings;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.archiver.MavenArchiveConfiguration;
import org.apache.maven.archiver.MavenArchiver;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.codehaus.plexus.archiver.jar.JarArchiver;
import org.codehaus.plexus.util.IOUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author Johan Lindquist <johanlindquist@gmail.com>
 */
@Mojo( name = "ndk-build", defaultPhase = LifecyclePhase.COMPILE )
public class NdkBuildMojo extends AbstractMojo
{
    /**
     * The <code>ANDROID_NDK_HOME</code> environment variable name.
     */
    public static final String ENV_ANDROID_NDK_HOME = "ANDROID_NDK_HOME";

    /**
     * <p>Parameter designed to pick up <code>-Dandroid.ndk.ndkPath</code> in case there is no pom with an
     * <code>&lt;ndk&gt;</code> configuration tag.</p>
     */
    @Parameter( property = "android.ndk.ndkPath", readonly = true )
    private File ndkPath;

    /**
     * Allows for overriding the default ndk-build executable.
     */
    @Parameter( property = "android.ndk.ndkBuildExecutable" )
    private String ndkBuildExecutable;

    /**
     * Folder in which the NDK makefiles are constructed.
     */
    @Parameter( property = "android.ndk.buildDirectory", defaultValue = "${project.build.directory}/android-ndk-maven-plugin", readonly = true )
    private File buildDirectory;

    /** Folder in which the ndk-build command is executed.  This is using the -C command line flag.
     */
    @Parameter( property = "android.ndk.workingDirectory", defaultValue = "${project.basedir}", readonly = true )
    private File workingDirectory;

    /** Specifies the classifier with which the artifact should be stored in the repository
     */
    @Parameter( property = "android.ndk.classifier" )
    private String classifier;

    /** Specifies additional command line parameters to pass to ndk-build
     */
    @Parameter( property = "android.ndk.additionalCommandline" )
    protected String additionalCommandline;

    /**
     * <p>Folder containing native, static libraries compiled and linked by the NDK.</p>
     * <p/>
     */
    @Parameter( property = "android.ndk.objectsOutputDirectory", defaultValue = "${project.build.directory}/obj" )
    private File objectsOutputDirectory;

    /**
     * <p>Folder containing native, static libraries compiled and linked by the NDK.</p>
     * <p/>
     */
    @Parameter( property = "android.ndk.librariesOutputDirectory", defaultValue = "${project.build.directory}/ndk-libs" )
    private File librariesOutputDirectory;

    /**
     * <p>Target to invoke on the native makefile.</p>
     */
    @Parameter( property = "android.ndk.target" )
    private String target;

    /**
     * Defines the architectures for the NDK build - this is a space separated list (i.e x86 armeabi)
     */
    @Parameter( property = "android.ndk.architectures" )
    private String architectures;

    /**
     * Defines the architecture to toolchain mappings for the NDK build
     * &lt;architectureToolchainMappings&gt;
     * &lt;x86&gt;x86-4.7&lt;/x86&gt;
     * &lt;armeabi&gt;arm-linux-androideabi-4.7&lt;/armeabi&gt;
     * &lt;/architectureToolchainMappings&gt;
     */
    @Parameter
    private ArchitectureToolchainMappings architectureToolchainMappings;

    /**
     * Flag indicating whether the header files used in the build should be included and attached to the build as
     * an additional artifact.
     */
    @Parameter( property = "android.ndk.attachHeaderFiles", defaultValue = "true" )
    private Boolean attachHeaderFiles;

    /**
     * Flag indicating whether the final artifacts should be included and attached to the build as an artifact.
     */
    @Parameter( property = "android.ndk.attachLibrariesArtifacts", defaultValue = "true" )
    private Boolean attachLibrariesArtifacts;


    /**
     * Flag indicating whether the make files last LOCAL_SRC_INCLUDES should be used for determining what header
     * files to include.  Setting this flag to true, overrides any defined header files directives.
     * <strong>Note: </strong> By setting this flag to true, all header files used in the project will be
     * added to the resulting header archive.  This may be undesirable in most cases and is therefore turned off by
     * default.
     */
    @Parameter( property = "android.ndk.useLocalSrcIncludePaths", defaultValue = "false" )
    private Boolean useLocalSrcIncludePaths;

    /**
     * Specifies the set of header files includes/excludes which should be used for bundling the exported header
     * files.  The below shows an example of how this can be used.
     * <p/>
     * <pre>
     * &lt;headerFilesDirectives&gt;
     *   &lt;headerFilesDirective&gt;
     *     &lt;directory&gt;${basedir}/jni/include&lt;/directory&gt;
     *     &lt;includes&gt;
     *       &lt;includes&gt;**\/*.h&lt;/include&gt;
     *     &lt;/includes&gt;
     *   &lt;headerFilesDirective&gt;
     * &lt;/headerFilesDirectives&gt;
     * </pre>
     * <br/>
     * If no <code>headerFilesDirectives</code> is specified, the default includes will be defined as shown below:
     * <br/>
     * <pre>
     * &lt;headerFilesDirectives&gt;
     *   &lt;headerFilesDirective&gt;
     *     &lt;directory&gt;${basedir}/jni&lt;/directory&gt;
     *     &lt;includes&gt;
     *       &lt;includes&gt;**\/*.h&lt;/include&gt;
     *     &lt;/includes&gt;
     *     &lt;excludes&gt;
     *       &lt;exclude&gt;**\/*.c&lt;/exclude&gt;
     *     &lt;/excludes&gt;
     *   &lt;headerFilesDirective&gt;
     *   [..]
     * &lt;/headerFilesDirectives&gt;
     * </pre>
     */
    @Parameter

    private List<HeaderFilesDirective> headerFilesDirectives;

    /**
     * Flag indicating whether the header files for native, static library dependencies should be used.  If true,
     * the header archive for each statically linked dependency will be resolved.
     */
    @Parameter( property = "android.ndk.build.use-header-archive", defaultValue = "true" )
    private Boolean useHeaderArchives;

    /**
     * Defines additional system properties which should be exported to the ndk-build script.  This
     * <br/>
     * <pre>
     * &lt;systemProperties&gt;
     *   &lt;propertyName&gt;propertyValue&lt;/propertyName&gt;
     *   &lt;build-target&gt;android&lt;/build-target&gt;
     *   [..]
     * &lt;/systemProperties&gt;
     * </pre>     *
     */
    @Parameter
    private Map<String, String> systemProperties;

    /**
     * Flag indicating whether warnings should be ignored while compiling.  If true,
     * the build will not fail if warning are found during compile.
     */
    @Parameter( property = "android.ndk.ignoreBuildWarnings", defaultValue = "true" )
    private Boolean ignoreBuildWarnings;

    /**
     * Defines the regular expression used to detect whether error/warning output from ndk-build is a minor compile
     * warning or is actually an error which should cause the build to fail.
     * <p/>
     * If the pattern matches, the output from the compiler will <strong>not</strong> be considered an error and compile
     * will be successful.
     */
    @Parameter( property = "android.ndk.buildWarningsRegularExpression", defaultValue = ".*[warning|note]: .*" )
    private String buildWarningsRegularExpression;

    /** Specifies the NDK toolchain to use for the build.  This will be using the NDK_TOOLCHAIN define on the ndk-build commandline.
     */
    @Parameter( property = "android.ndk.build.ndk-toolchain" )
    private String ndkToolchain;

    /**
     * Specifies the final name of the library output by the build (this allows the pom to override the default artifact name).
     * The value should not include the 'lib' prefix or filename extension (e.g. '.so').
     */
    @Parameter( property = "android.ndk.finalLibraryName" )
    private String finalLibraryName;

    /**
     * Specifies the makefile to use for the build (if other than the default Android.mk).
     */
    @Parameter( property = "android.ndk.makefile" )
    private String makefile;

    /**
     * Specifies the application makefile to use for the build (if other than the default Application.mk).
     */
    @Parameter( property = "android.ndk.applicationMakefile" )
    private String applicationMakefile;

    /**
     * Flag indicating whether to use the max available jobs for the host machine
     */
    @Parameter( property = "android.ndk.maxJobs", defaultValue = "false" )
    private Boolean maxJobs;

    /**
     *
     */
    @Parameter()
    private List<AdditionallyBuiltModule> additionallyBuiltModules;

    /**
     * Flag indicating whether or not the build should be skipped entirely
     */
    @Parameter( defaultValue = "false" )
    private boolean skip;

    /**
     * Flag indicating whether or not multiple number of native libraries should be included
     */
    @Parameter( defaultValue = "false" )
    private boolean allowMultiArtifacts;

    /**
     * The Jar archiver.
     */
    @Component( role = org.codehaus.plexus.archiver.Archiver.class, hint = "jar" )
    private JarArchiver jarArchiver;

    @Component( role = org.apache.maven.artifact.handler.ArtifactHandler.class, hint = "har" )
    private ArtifactHandler harArtifactHandler;

    /**
     * The maven project.
     */
    @Parameter( defaultValue = "${project}", readonly = true, required = true )
    protected MavenProject project;

    /**
     * Maven ProjectHelper.
     */
    @Component
    protected MavenProjectHelper projectHelper;

    @Component
    private ArtifactResolver artifactResolver;

    /**
     * Dependency graph builder component.
     */
    @Component( hint = "default" )
    protected DependencyGraphBuilder dependencyGraphBuilder;

    private ArtifactResolverHelper artifactResolverHelper;
    private NativeHelper nativeHelper;

    /**
     * @throws MojoExecutionException
     * @throws MojoFailureException
     */
    public void execute() throws MojoExecutionException, MojoFailureException
    {
        if ( skip )
        {
            getLog().info( "Skipping execution as per configuration" );
            return;
        }

        if ( !attachLibrariesArtifacts && NativeHelper.isNativeArtifactProject( project ) )
        {
            getLog().warn( "Configured to not attach artifacts, this may cause an error at install/deploy time" );
        }

        // Validate the NDK
        final File ndkBuildFile = new File( getAndroidNdk().getNdkBuildPath() );
        NativeHelper.validateNDKVersion( ndkBuildFile.getParentFile() );

        validateMakefile( project, makefile );

        final String[] resolvedNDKArchitectures = NativeHelper.getNdkArchitectures( architectures, applicationMakefile, project.getBasedir() );

        for ( String architecture : resolvedNDKArchitectures )
        {
            try
            {
                compileForArchitecture( architecture );
            }
            catch ( Exception e )
            {
                getLog().error( "Error while executing: " + e.getMessage() );
                throw new MojoExecutionException( e.getMessage(), e );
            }
        }
    }

    private void compileForArchitecture( String architecture ) throws MojoExecutionException, IOException, ExecutionException
    {
        MakefileHelper.MakefileHolder makefileHolder = null;
        try
        {
            getLog().debug( "Resolving for NDK architecture : " + architecture );

            // Start setting up the command line to be executed
            final CommandExecutor executor = CommandExecutor.Factory.createDefaultCommmandExecutor();
            // Add an error listener to the build - this allows the build to conditionally fail
            // depending on a) the output of the build b) whether or not build errors (output on stderr) should be
            // ignored and c) whether the pattern matches or not
            executor.setErrorListener( getNdkErrorListener() );

            final Set<Artifact> nativeLibraryArtifacts = findNativeLibraryDependencies();

            // If there are any static libraries the code needs to link to, include those in the make file
            final Set<Artifact> resolvedNativeLibraryArtifacts = getArtifactResolverHelper().resolveArtifacts( nativeLibraryArtifacts );

            getLog().debug( "resolveArtifacts found " + resolvedNativeLibraryArtifacts.size() + ": " + resolvedNativeLibraryArtifacts.toString() );

            final File buildFolder = new File( buildDirectory, architecture );
            buildFolder.mkdirs();

            final File androidMavenMakefile = new File( buildFolder, "android_maven_plugin_makefile.mk" );
            final MakefileHelper makefileHelper = new MakefileHelper( getLog(), getArtifactResolverHelper(), harArtifactHandler, null, buildDirectory );
            makefileHolder = makefileHelper.createMakefileFromArtifacts( resolvedNativeLibraryArtifacts, architecture, "armeabi", useHeaderArchives );

            final FileOutputStream output = new FileOutputStream( androidMavenMakefile );
            try
            {
                IOUtil.copy( makefileHolder.getMakeFile(), output );
            }
            finally
            {
                output.close();
            }

            // Add the path to the generated makefile - this is picked up by the build (by an include from the user)
            executor.addEnvironment( "ANDROID_MAVEN_PLUGIN_MAKEFILE", androidMavenMakefile.getAbsolutePath() );

            setupNativeLibraryEnvironment( makefileHelper, executor, resolvedNativeLibraryArtifacts, architecture );

            // Adds the location of the Makefile capturer file - this file will after the build include
            // things like header files, flags etc.  It is processed after the build to retrieve the headers
            // and also capture flags etc ...
            final File makefileCaptureFile = File.createTempFile( "android_maven_plugin_makefile_captures", ".tmp" , buildDirectory );
            makefileCaptureFile.deleteOnExit();
            executor.addEnvironment( MakefileHelper.MAKEFILE_CAPTURE_FILE, makefileCaptureFile.getAbsolutePath() );

            // Add any defined system properties
            if ( systemProperties != null && !systemProperties.isEmpty() )
            {
                for ( Map.Entry<String, String> entry : systemProperties.entrySet() )
                {
                    executor.addEnvironment( entry.getKey(), entry.getValue() );
                }
            }

            executor.setLogger( this.getLog() );

            // Setup the command line for the make
            final List<String> commands = new ArrayList<String>();

            configureBuildDirectory( commands );

            configureMakefile( commands );

            configureApplicationMakefile( commands );

            configureMaxJobs( commands );

            configureNdkToolchain( architecture, commands );

            configureAdditionalCommands( commands );

            commands.add( "NDK_LIBS_OUT=" + librariesOutputDirectory.getAbsolutePath() );
            commands.add( "NDK_OUT=" + objectsOutputDirectory.getAbsolutePath() );

            // If a build target is specified, tag that onto the command line as the very last of the parameters
            commands.add( target != null ? target : "all" );

            final String ndkBuildPath = resolveNdkBuildExecutable();
            getLog().debug( ndkBuildPath + " " + commands.toString() );
            getLog().info( "Executing NDK " + architecture + " make at : " + buildDirectory );

            executor.setCaptureStdOut( true );
            executor.executeCommand( ndkBuildPath, commands, buildDirectory, true );
            getLog().debug( "Executed NDK " + architecture + " make at : " + buildDirectory );

            if ( attachLibrariesArtifacts )
            {
                // Attempt to attach the native libraries (shared only)
                processCompiledArtifacts( architecture, makefileCaptureFile );
            }
            else
            {
                getLog().info( "Will skip attaching compiled libraries as per configuration" );
            }

        }
        finally
        {
            // If we created a makefile for the build we should be polite and remove any extracted include directories after we're done
            cleanupAfterArchitectureBuild( makefileHolder );
        }

    }

    private void configureBuildDirectory( final List<String> commands )
    {
        // Setup the build directory (defaults to the current directory) but may be different depending
        // on user configuration
        commands.add( "-C" );
        commands.add( workingDirectory.getAbsolutePath() );
    }

    private void configureMakefile( final List<String> commands ) throws MojoExecutionException
    {
        // If the build should use a custom makefile or not - some validation is done to ensure
        // this exists and all
        if ( makefile != null )
        {
            File makeFile = new File( project.getBasedir(), makefile );
            if ( !makeFile.exists() )
            {
                getLog().error( "Specified makefile " + makeFile + " does not exist" );
                throw new MojoExecutionException( "Specified makefile " + makeFile + " does not exist" );
            }
            commands.add( "APP_BUILD_SCRIPT=" + makefile );
        }
    }

    private void cleanupAfterArchitectureBuild( final MakefileHelper.MakefileHolder makefileHolder )
    {
        // directories after we're done
        if ( makefileHolder != null )
        {
            getLog().info( "Cleaning up extracted include directories used for build" );
            MakefileHelper.cleanupAfterBuild( makefileHolder );
        }
    }

    private void configureAdditionalCommands( final List<String> commands )
    {
        // Anything else on the command line the user wants to add - simply splice it up and
        // add it one by one to the command line
        if ( additionalCommandline != null )
        {
            final String[] additionalCommands = additionalCommandline.split( " " );
            commands.addAll( Arrays.asList( additionalCommands ) );
        }
    }

    private void configureApplicationMakefile( List<String> commands )
            throws MojoExecutionException
    {
        if ( applicationMakefile != null )
        {
            File appMK = new File( project.getBasedir(), applicationMakefile );
            if ( !appMK.exists() )
            {
                getLog().error( "Specified application makefile " + appMK + " does not exist" );
                throw new MojoExecutionException( "Specified application makefile " + appMK + " does not exist" );
            }
            commands.add( "NDK_APPLICATION_MK=" + applicationMakefile );
        }
    }

    private void configureMaxJobs( List<String> commands )
    {
        if ( maxJobs )
        {
            String jobs = String.valueOf( Runtime.getRuntime().availableProcessors() );
            getLog().info( "executing " + jobs + " parallel jobs" );
            commands.add( "-j" );
            commands.add( jobs );
        }
    }

    private void configureNdkToolchain( String architecture, List<String> commands )
            throws MojoExecutionException
    {
        if ( ndkToolchain != null )
        {
            // Setup the correct toolchain to use
            // FIXME: perform a validation that this toolchain exists in the NDK
            commands.add( "NDK_TOOLCHAIN=" + ndkToolchain );
        }
        else
        {
            // Resolve the toolchain from the architecture
            // <architectures>
            //   <x86>x86-4.6</x86>
            //   <armeabi>x86-4.6</armeabi>
            // </architectures>
            final String toolchainFromArchitecture = getAndroidNdk().getToolchainFromArchitecture( architecture, architectureToolchainMappings );
            getLog().debug( "Resolved toolchain for " + architecture + " to " + toolchainFromArchitecture );
            commands.add( "NDK_TOOLCHAIN=" + toolchainFromArchitecture );
            commands.add( "APP_ABI=" + architecture );

        }
    }

    /**
     * Attaches native libs to project.
     */
    private void processCompiledArtifacts( String architecture, final File makefileCaptureFile ) throws IOException, MojoExecutionException
    {
        // Where the NDK build creates the libs.
        final File nativeLibraryDirectory = new File( librariesOutputDirectory, architecture );

        // Where the NDK build creates the object files - static files end up here
        final File nativeObjDirectory = new File( new File( objectsOutputDirectory, "local" ), architecture );

        final List<String> classifiers = new ArrayList<String>();
        if ( allowMultiArtifacts )
        {
            attachManyArtifacts( nativeLibraryDirectory, architecture, nativeObjDirectory, classifiers );
        }
        else
        {
            attachOneArtifact( nativeLibraryDirectory, architecture, nativeObjDirectory, classifiers );
        }

        if ( additionallyBuiltModules != null && !additionallyBuiltModules.isEmpty() )
        {
            for ( AdditionallyBuiltModule additionallyBuiltModule : additionallyBuiltModules )
            {
                File additionalBuiltModuleFile = nativeLibraryFromName( true, nativeLibraryDirectory, additionallyBuiltModule.getName() );

                // If it doesnt exist, check the object directory
                if ( !additionalBuiltModuleFile.exists() )
                {
                    additionalBuiltModuleFile = nativeLibraryFromName( true, nativeObjDirectory, additionallyBuiltModule.getName() );
                }

                // FIMXE: This should be validated
                final String additionallyBuiltArtifactType = resolveArtifactType( additionalBuiltModuleFile );

                String additionallyBuiltClassifier = architecture + "-" + additionallyBuiltModule.getClassifier();
                projectHelper.attachArtifact( this.project, additionallyBuiltArtifactType, additionallyBuiltClassifier, additionalBuiltModuleFile );
                classifiers.add( additionallyBuiltClassifier );
            }
        }

        // Process conditionally any of the headers to include into the header archive file
        if ( attachHeaderFiles )
        {
            attachHeaderFiles( makefileCaptureFile, classifiers );
        }


    }

    private void attachManyArtifacts( File nativeLibraryDirectory, String architecture, File nativeObjDirectory, List<String> classifiers ) throws MojoExecutionException
    {
        List<File> artifacts = Arrays.asList( findNativeLibrary( nativeLibraryDirectory, nativeObjDirectory ) );
        for ( File file : artifacts )
        {
            attachArtifactFile( architecture, classifiers, file );
        }
    }

    private void attachOneArtifact( File nativeLibraryDirectory, String architecture, File nativeObjDirectory, List<String> classifiers ) throws MojoExecutionException
    {
        final File nativeArtifactFile;
        if ( finalLibraryName == null )
        {
            nativeArtifactFile = findNativeLibrary( nativeLibraryDirectory, nativeObjDirectory )[0];
        }
        else
        {
            nativeArtifactFile = nativeLibraryFromName( nativeLibraryDirectory, nativeObjDirectory, finalLibraryName );
        }

        attachArtifactFile( architecture, classifiers, nativeArtifactFile );
    }

    private void attachArtifactFile( String architecture, List<String> classifiers, File nativeArtifactFile )
    {
        final String artifactType = resolveArtifactType( nativeArtifactFile );
        getLog().debug( "Adding native compiled artifact: " + nativeArtifactFile );

        final String actualClassifier = ( classifier == null ) ? architecture : architecture + "-" + classifier;
        projectHelper.attachArtifact( this.project, artifactType, actualClassifier, nativeArtifactFile );
        classifiers.add( actualClassifier );
    }

    /**
     * Search the specified directory for native artifacts that match the artifact Id
     */
    private File[] findNativeLibrary( File nativeLibDirectory, final File nativeObjDirectory ) throws MojoExecutionException
    {
        getLog().info( "Searching " + nativeLibDirectory + " for built shared library" );
        // FIXME: Should really just look for shared libraries in here really ....
        File[] files = nativeLibDirectory.listFiles( new FilenameFilter()
        {
            public boolean accept( final File dir, final String name )
            {
                String libraryName = finalLibraryName;

                if ( libraryName == null || libraryName.isEmpty() )
                {
                    libraryName = project.getArtifactId();
                }

                // FIXME: The following logic won't work for an APKLIB building a static library
                final String extension = Const.ArtifactType.NATIVE_IMPLEMENTATION_ARCHIVE.equals( project.getPackaging() ) ? ".a" : ".so";
                boolean found = name.startsWith( "lib" + libraryName ) && name.endsWith( extension );
                if ( !found )
                {
                    // Issue #14 : Work-around issue where the project is actually called "lib" something
                    if ( libraryName.startsWith( "lib" ) )
                    {
                        found = name.startsWith( libraryName ) && name.endsWith( extension );
                    }
                }
                return found;
            }
        } );

        // Check the object output directory as well
        // FIXME: Should really just look for static libraries in here really ....

        if ( files == null || files.length == 0 )
        {
            getLog().info( "Searching " + nativeObjDirectory + " for built static library" );
            files = nativeObjDirectory.listFiles( new FilenameFilter()
            {
                public boolean accept( final File dir, final String name )
                {
                    String libraryName = finalLibraryName;

                    if ( libraryName == null || libraryName.isEmpty() )
                    {
                        libraryName = project.getArtifactId();
                    }

                    // FIXME: The following logic won't work for an APKLIB building a static library
                    if ( Const.ArtifactType.NATIVE_IMPLEMENTATION_ARCHIVE.equals( project.getPackaging() ) )
                    {
                        return name.startsWith( "lib" + libraryName ) && name.endsWith( ".a" );
                    }
                    else
                    {
                        return name.startsWith( "lib" + libraryName ) && name.endsWith( ".so" );
                    }
                }
            } );

        }

        // slight limitation at this stage - we only handle a single .so artifact
        if ( ( files == null || files.length != 1 )  && !allowMultiArtifacts )
        {
            getLog().warn( "Error while detecting native compile artifacts: " + ( files == null || files.length == 0 ? "None found" : "Found more than 1 artifact" ) );
            if ( target != null )
            {
                getLog().warn( "Using the 'target' configuration option to specify the output file name is no longer supported, use 'finalLibraryName' instead." );
            }

            if ( files != null && files.length > 1 )
            {
                getLog().debug( "List of files found: " + Arrays.asList( files ) );
                getLog().error( "Currently, only a single, final native library is supported by the build" );
                throw new MojoExecutionException( "Currently, only a single, final native library is supported by the build" );
            }
            else
            {
                getLog().error( "No native compiled library found, did the native compile complete successfully?" );
                throw new MojoExecutionException( "No native compiled library found, did the native compile complete successfully?" );
            }
        }
        return files;
    }

    private File nativeLibraryFromName( File nativeLibDirectory, final File nativeObjDirectory, final String libraryName ) throws MojoExecutionException
    {
        try
        {
            return nativeLibraryFromName( false, nativeLibDirectory, libraryName );
        }
        catch ( MojoExecutionException e )
        {
            // Try the obj directory
            return nativeLibraryFromName( true, nativeObjDirectory, libraryName );
        }
    }

    private File nativeLibraryFromName( boolean logErrors, File directory, final String libraryName ) throws MojoExecutionException
    {
        final File libraryFile;
        // Find the nativeArtifactFile in the nativeLibDirectory/finalLibraryName
        if ( Const.ArtifactType.NATIVE_SYMBOL_OBJECT.equals( project.getPackaging() ) || Const.ArtifactType.NATIVE_IMPLEMENTATION_ARCHIVE.equals( project.getPackaging() ) )
        {
            libraryFile = new File( directory, "lib" + libraryName + "." + project.getPackaging() );
        }
        else
        {
            final File staticLib = new File( directory, "lib" + libraryName + ".a" );
            if ( staticLib.exists() )
            {
                libraryFile = staticLib;
            }
            else
            {
                libraryFile = new File( directory, "lib" + libraryName + ".so" );
            }
        }
        if ( !libraryFile.exists() )
        {
            if ( logErrors )
            {
                getLog().error( "Could not locate final native library using the provided finalLibraryName " + libraryName + " (tried " + libraryFile.getAbsolutePath() + ")" );
            }
            throw new MojoExecutionException( "Could not locate final native library using the provided finalLibraryName " + libraryName + " (tried " + libraryFile.getAbsolutePath() + ")" );
        }

        return libraryFile;
    }


    private CommandExecutor.ErrorListener getNdkErrorListener()
    {
        return new CommandExecutor.ErrorListener()
        {
            @Override
            public boolean isError( String error )
            {

                // Unconditionally ignore *All* build warning if configured to
                if ( ignoreBuildWarnings )
                {
                    return false;
                }

                final Pattern pattern = Pattern.compile( buildWarningsRegularExpression );
                final Matcher matcher = pattern.matcher( error );

                // If the the reg.exp actually matches, we can safely say this is not an error
                // since in theory the user told us so
                if ( matcher.matches() )
                {
                    return false;
                }

                // Otherwise, it is just another error
                return true;
            }
        };
    }

    /**
     * Validate the makefile - if our packaging type is so (for example) and there are
     * dependencies on .a files (or shared files for that matter) the makefile should include
     * the include of our Android Maven plugin generated makefile.
     */
    private void validateMakefile( MavenProject project, String file )
    {
        // TODO: actually perform validation
    }

    private String resolveNdkBuildExecutable() throws MojoExecutionException
    {
        if ( ndkBuildExecutable != null )
        {
            getLog().debug( "ndk-build overriden, using " + ndkBuildExecutable );
            return ndkBuildExecutable;
        }
        return getAndroidNdk().getNdkBuildPath();
    }

    private void attachHeaderFiles( File localCIncludesFile, final List<String> classifiers ) throws MojoExecutionException, IOException
    {

        final List<HeaderFilesDirective> finalHeaderFilesDirectives = new ArrayList<HeaderFilesDirective>();

        if ( useLocalSrcIncludePaths )
        {
            Properties props = new Properties();
            props.load( new FileInputStream( localCIncludesFile ) );
            String localCIncludes = props.getProperty( "LOCAL_C_INCLUDES" );
            if ( localCIncludes != null && !localCIncludes.trim().isEmpty() )
            {
                String[] includes = localCIncludes.split( " " );
                for ( String include : includes )
                {
                    final HeaderFilesDirective headerFilesDirective = new HeaderFilesDirective();
                    File includeDir = new File( project.getBasedir(), include );
                    headerFilesDirective.setDirectory( includeDir.getAbsolutePath() );
                    headerFilesDirective.setIncludes( new String[]{ "**/*.h" } );
                    finalHeaderFilesDirectives.add( headerFilesDirective );
                }
            }
        }
        else
        {
            if ( headerFilesDirectives != null )
            {
                finalHeaderFilesDirectives.addAll( headerFilesDirectives );
            }
        }
        if ( finalHeaderFilesDirectives.isEmpty() )
        {
            getLog().debug( "No header files included, will add default set" );
            final HeaderFilesDirective e = new HeaderFilesDirective();
            final File folder = new File( project.getBasedir() + "/jni" );
            if ( folder.exists() )
            {
                e.setDirectory( folder.getAbsolutePath() );
                e.setIncludes( new String[] { "**/*.h" } );
                finalHeaderFilesDirectives.add( e );
            }
        }
        createHeaderArchive( finalHeaderFilesDirectives, classifiers );
    }

    private void createHeaderArchive( List<HeaderFilesDirective> finalHeaderFilesDirectives, final List<String> classifiers ) throws MojoExecutionException
    {
        try
        {
            MavenArchiver mavenArchiver = new MavenArchiver();
            mavenArchiver.setArchiver( jarArchiver );

            final File jarFile = File.createTempFile( "tmp", ".har", buildDirectory );

            mavenArchiver.setOutputFile( jarFile );

            for ( HeaderFilesDirective headerFilesDirective : finalHeaderFilesDirectives )
            {
                mavenArchiver.getArchiver().addDirectory( new File( headerFilesDirective.getDirectory() ), headerFilesDirective.getIncludes(), headerFilesDirective.getExcludes() );
            }

            final MavenArchiveConfiguration mavenArchiveConfiguration = new MavenArchiveConfiguration();
            mavenArchiveConfiguration.setAddMavenDescriptor( false );

            mavenArchiver.createArchive( project, mavenArchiveConfiguration );

            for ( String classifier : classifiers )
            {
                getLog().debug( "Attaching 'har' classifier=" + classifier + " file=" + jarFile );
                projectHelper.attachArtifact( project, Const.ArtifactType.NATIVE_HEADER_ARCHIVE, classifier, jarFile );
            }

        }
        catch ( Exception e )
        {
            throw new MojoExecutionException( e.getMessage() );
        }
    }

    private void setupNativeLibraryEnvironment( MakefileHelper makefileHelper, CommandExecutor executor,
                                                Set<Artifact> resolveNativeLibraryArtifacts, String architecture )
    {
        // Only add the LOCAL_STATIC_LIBRARIES
        if ( NativeHelper.hasStaticNativeLibraryArtifact( resolveNativeLibraryArtifacts, architecture ) )
        {
            String staticlibs = makefileHelper.createLibraryList( resolveNativeLibraryArtifacts, architecture, true );
            executor.addEnvironment( "ANDROID_MAVEN_PLUGIN_LOCAL_STATIC_LIBRARIES", staticlibs );
            getLog().debug( "Set ANDROID_MAVEN_PLUGIN_LOCAL_STATIC_LIBRARIES = " + staticlibs );
        }

        // Only add the LOCAL_SHARED_LIBRARIES
        if ( NativeHelper.hasSharedNativeLibraryArtifact( resolveNativeLibraryArtifacts, architecture ) )
        {
            String sharedlibs = makefileHelper.createLibraryList( resolveNativeLibraryArtifacts, architecture, false );
            executor.addEnvironment( "ANDROID_MAVEN_PLUGIN_LOCAL_SHARED_LIBRARIES", sharedlibs );
            getLog().debug( "Set ANDROID_MAVEN_PLUGIN_LOCAL_SHARED_LIBRARIES = " + sharedlibs );
        }
    }

    private Set<Artifact> findNativeLibraryDependencies() throws MojoExecutionException
    {
        final NativeHelper nativeHelper = getNativeHelper();
        final Set<Artifact> staticLibraryArtifacts = nativeHelper.getNativeDependenciesArtifacts( false );
        final Set<Artifact> sharedLibraryArtifacts = nativeHelper.getNativeDependenciesArtifacts( true );

        final Set<Artifact> mergedArtifacts = new LinkedHashSet<Artifact>();
        filterNativeDependencies( mergedArtifacts, staticLibraryArtifacts );
        filterNativeDependencies( mergedArtifacts, sharedLibraryArtifacts );

        getLog().debug( "findNativeLibraryDependencies found " + mergedArtifacts.size() + ": " + mergedArtifacts.toString() );

        return mergedArtifacts;
    }

    /**
     * Selectively add artifacts from source to target excluding any whose groupId and artifactId match
     * the current build.
     * <p/>
     * Introduced to work around an issue when the ndk-build is executed twice by maven for example when
     * invoking maven 'install site'. In this case the artifacts attached by the first invocation are
     * found but are not valid dependencies and must be excluded.
     *
     * @param targetSet artifact Set to copy in to
     * @param source    artifact Set to filter
     */
    private void filterNativeDependencies( Set<Artifact> targetSet, Set<Artifact> source )
    {
        for ( Artifact a : source )
        {
            if ( project.getGroupId().equals( a.getGroupId() ) && project.getArtifactId().equals( a.getArtifactId() ) )
            {
                getLog().warn( "Excluding native dependency attached by this build" );
            }
            else
            {
                targetSet.add( a );
            }
        }
    }

    /**
     * Resolve the artifact type from the current project and the specified file.  If the project packaging is
     * either 'a' or 'so' it will use the packaging, otherwise it checks the file for the extension
     *
     * @param file The file being added as an artifact
     * @return The artifact type (so or a)
     */
    private String resolveArtifactType( File file )
    {
        if ( Const.ArtifactType.NATIVE_SYMBOL_OBJECT.equals( project.getPackaging() ) || Const.ArtifactType.NATIVE_IMPLEMENTATION_ARCHIVE.equals( project.getPackaging() ) )
        {
            return project.getPackaging();
        }
        else
        {
            // At this point, the file (as found by our filtering previously will end with either 'so' or 'a'
            return file.getName().endsWith( Const.ArtifactType.NATIVE_SYMBOL_OBJECT ) ? Const.ArtifactType.NATIVE_SYMBOL_OBJECT : Const.ArtifactType.NATIVE_IMPLEMENTATION_ARCHIVE;
        }
    }

    /**
     * <p>Returns the Android NDK to use.</p>
     * <p/>
     * <p>Current implementation looks for <code>&lt;ndk&gt;&lt;path&gt;</code> configuration in pom, then System
     * property <code>android.ndk.path</code>, then environment variable <code>ANDROID_NDK_HOME</code>.
     * <p/>
     * <p>This is where we collect all logic for how to lookup where it is, and which one to choose. The lookup is
     * based on available parameters. This method should be the only one you should need to look at to understand how
     * the Android NDK is chosen, and from where on disk.</p>
     *
     * @return the Android NDK to use.
     * @throws org.apache.maven.plugin.MojoExecutionException if no Android NDK path configuration is available at all.
     */
    protected AndroidNdk getAndroidNdk() throws MojoExecutionException
    {
        final File chosenNdkPath;

        if ( ndkPath != null )
        {
            // -Dandroid.ndk.path is set on command line, or via <properties><ndk.path>...
            chosenNdkPath = ndkPath;
        }
        else
        {
            // No -Dandroid.ndk.path is set on command line, or via <properties><ndk.path>...
            chosenNdkPath = new File( getAndroidNdkHomeOrThrow() );
        }

        return new AndroidNdk( chosenNdkPath );
    }


    /**
     * @return
     * @throws MojoExecutionException
     */
    private String getAndroidNdkHomeOrThrow() throws MojoExecutionException
    {
        final String androidHome = System.getenv( ENV_ANDROID_NDK_HOME );
        if ( StringUtils.isBlank( androidHome ) )
        {
            throw new MojoExecutionException( "No Android NDK path could be found. You may configure it in the pom using <ndkPath>...</ndkPath> or "
                    + "<properties><android.ndk.path>...</android.ndk.path></properties> or on command-line using -Dandroid.ndk.path=... "
                    + "or by setting environment variable " + ENV_ANDROID_NDK_HOME );
        }
        return androidHome;
    }

    protected final ArtifactResolverHelper getArtifactResolverHelper()
    {
        if ( artifactResolverHelper == null )
        {
            artifactResolverHelper = new ArtifactResolverHelper( artifactResolver, new MavenToPlexusLogAdapter( getLog() ), project.getRemoteArtifactRepositories() );
        }
        return artifactResolverHelper;
    }

    protected final NativeHelper getNativeHelper()
    {
        if ( nativeHelper == null )
        {
            nativeHelper = new NativeHelper( project, dependencyGraphBuilder, getLog() );
        }
        return nativeHelper;
    }


}
