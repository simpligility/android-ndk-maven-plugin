package com.simpligility.maven.plugins.androidndk.configuration;

/**
 * @author
 */
public class AdditionallyBuiltModule
{
    String name;
    String fileName;
    String type;
    String classifier;

    public String getName()
    {
        return name;
    }

    public void setName( final String name )
    {
        this.name = name;
    }

    public String getClassifier()
    {
        return classifier;
    }

    public void setClassifier( final String classifier )
    {
        this.classifier = classifier;
    }

    public String getFileName()
    {
        return fileName;
    }

    public void setFileName( final String fileName )
    {
        this.fileName = fileName;
    }

    public String getType()
    {
        return type;
    }

    public void setType( final String type )
    {
        this.type = type;
    }
}
