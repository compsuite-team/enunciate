/**
 * Copyright © 2006-2016 Web Cohesion (info@webcohesion.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.webcohesion.enunciate.modules.jaxrs;

import com.webcohesion.enunciate.CompletionFailureException;
import com.webcohesion.enunciate.EnunciateContext;
import com.webcohesion.enunciate.EnunciateException;
import com.webcohesion.enunciate.api.ApiRegistry;
import com.webcohesion.enunciate.module.*;
import com.webcohesion.enunciate.modules.jaxrs.model.*;
import com.webcohesion.enunciate.modules.jaxrs.model.util.MediaType;
import com.webcohesion.enunciate.util.AnnotationUtils;
import com.webcohesion.enunciate.util.PathSortStrategy;
import javassist.bytecode.ClassFile;

import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import java.util.*;

import static com.webcohesion.enunciate.util.AnnotationUtils.isIgnored;

/**
 * @author Ryan Heaton
 */
public class JaxrsModule extends BasicProviderModule implements TypeDetectingModule, ApiRegistryProviderModule, ApiFeatureProviderModule {

  private DataTypeDetectionStrategy defaultDataTypeDetectionStrategy;
  private final List<MediaTypeDefinitionModule> mediaTypeModules = new ArrayList<>();
  private EnunciateJaxrsContext jaxrsContext;
  static final String NAME = "jaxrs";
  private PathSortStrategy defaultSortStrategy = PathSortStrategy.breadth_first;

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public List<DependencySpec> getDependencySpecifications() {
    return Collections.singletonList(new MediaTypeDependencySpec());
  }

  public DataTypeDetectionStrategy getDataTypeDetectionStrategy() {
    String dataTypeDetection = this.config.getString("[@datatype-detection]", null);

    if (dataTypeDetection != null) {
      try {
        return DataTypeDetectionStrategy.valueOf(dataTypeDetection);
      }
      catch (IllegalArgumentException e) {
        //fall through...
      }
    }

    if (this.defaultDataTypeDetectionStrategy != null) {
      return this.defaultDataTypeDetectionStrategy;
    }

    if (this.enunciate.getIncludePatterns().isEmpty()) {
      //if there are no configured include patterns, then we'll just stick with "local" detection so we don't include too much.
      return DataTypeDetectionStrategy.local;
    }
    else {
      //otherwise, we'll assume the user knows what (s)he's doing and aggressively include everything.
      return DataTypeDetectionStrategy.aggressive;
    }
  }

  public void setDefaultDataTypeDetectionStrategy(DataTypeDetectionStrategy strategy) {
    this.defaultDataTypeDetectionStrategy = strategy;
  }

  public PathSortStrategy getPathSortStrategy()  {
    PathSortStrategy strategy = defaultSortStrategy;
    try {
      strategy = PathSortStrategy.valueOf(this.config.getString("[@path-sort-strategy]", this.defaultSortStrategy.name()));
    } catch (IllegalArgumentException e) {
      // Ignore?  Log?
    }
    return strategy;
  }

  public boolean isDisableExamples() {
    return this.config.getBoolean("[@disableExamples]", false);
  }

  public void setDefaultSortStrategy(PathSortStrategy defaultSortStrategy) {
    this.defaultSortStrategy = defaultSortStrategy;
  }

  public EnunciateJaxrsContext getJaxrsContext() {
    return jaxrsContext;
  }

  @Override
  public ApiRegistry getApiRegistry() {
    return new JaxrsApiRegistry(this.jaxrsContext);
  }

  @Override
  public void call(EnunciateContext context) {
    jaxrsContext = new EnunciateJaxrsContext(context, isDisableExamples());

    DataTypeDetectionStrategy detectionStrategy = getDataTypeDetectionStrategy();
    String relativeContextPath = "";
    if (detectionStrategy != DataTypeDetectionStrategy.passive) {
      Set<? extends Element> elements = detectionStrategy == DataTypeDetectionStrategy.local ? context.getLocalApiElements() : context.getApiElements();
      for (Element declaration : elements) {
        LinkedList<Element> contextStack = new LinkedList<>();
        contextStack.push(declaration);
        try {
          if (declaration instanceof TypeElement) {
            TypeElement element = (TypeElement) declaration;

            if ("org.glassfish.jersey.server.wadl.internal.WadlResource".equals(element.getQualifiedName().toString())) {
              //known internal wadl resource not to be documented.
              continue;
            }

            if (isIgnored(declaration)) {
              continue;
            }

            javax.ws.rs.Path pathInfo = declaration.getAnnotation(javax.ws.rs.Path.class);
            jakarta.ws.rs.Path pathInfo2 = declaration.getAnnotation(jakarta.ws.rs.Path.class);
            if (pathInfo != null || pathInfo2 != null) {
              //add root resource.
              RootResource rootResource = new RootResource(element, jaxrsContext);
              jaxrsContext.add(rootResource);
              for (ResourceMethod resourceMethod : rootResource.getResourceMethods(true)) {
                addReferencedDataTypeDefinitions(resourceMethod, contextStack);
              }
            }

            javax.ws.rs.ext.Provider providerInfo = declaration.getAnnotation(javax.ws.rs.ext.Provider.class);
            jakarta.ws.rs.ext.Provider providerInfo2 = declaration.getAnnotation(jakarta.ws.rs.ext.Provider.class);
            if (providerInfo != null || providerInfo2 != null) {
              //add jax-rs provider
              jaxrsContext.addJAXRSProvider(element);
            }

            javax.ws.rs.ApplicationPath applicationPathInfo = declaration.getAnnotation(javax.ws.rs.ApplicationPath.class);
            jakarta.ws.rs.ApplicationPath applicationPathInfo2 = declaration.getAnnotation(jakarta.ws.rs.ApplicationPath.class);
            if (applicationPathInfo != null || applicationPathInfo2 != null) {
              relativeContextPath = applicationPathInfo != null ? applicationPathInfo.value() : applicationPathInfo2.value();
            }
          }
        }
        catch (RuntimeException e) {
          if (e.getClass().getName().endsWith("CompletionFailure")) {
            throw new CompletionFailureException(contextStack, e);
          }

          throw e;
        }
        finally {
          contextStack.pop();
        }
      }
    }


    //tidy up the application path.
    relativeContextPath = this.config.getString("application[@path]", relativeContextPath);
    relativeContextPath = sanitizeContextPath(relativeContextPath);

    jaxrsContext.setRelativeContextPath(relativeContextPath);
    jaxrsContext.setGroupingStrategy(getGroupingStrategy());
    jaxrsContext.setPathSortStrategy(getPathSortStrategy());

    if (jaxrsContext.getRootResources().size() > 0) {
      this.enunciate.addArtifact(new JaxrsRootResourceClassListArtifact(this.jaxrsContext));
    }

    if (this.jaxrsContext.getProviders().size() > 0) {
      this.enunciate.addArtifact(new JaxrsProviderClassListArtifact(this.jaxrsContext));
    }
  }

  public static String sanitizeContextPath(String relativeContextPath) {
    while (relativeContextPath.startsWith("/")) {
      relativeContextPath = relativeContextPath.substring(1);
    }

    while (relativeContextPath.endsWith("/")) {
      //trim off any leading slashes
      relativeContextPath = relativeContextPath.substring(0, relativeContextPath.length() - 1);
    }

    return relativeContextPath;
  }

  /**
   * Add the referenced type definitions for the specified resource method.
   *
   * @param resourceMethod The resource method.
   */
  protected void addReferencedDataTypeDefinitions(ResourceMethod resourceMethod, LinkedList<Element> contextStack) {
    if (AnnotationUtils.isIgnored(resourceMethod)) {
      return;
    }

    ResourceEntityParameter ep = resourceMethod.getEntityParameter();
    if (ep != null) {
      Set<com.webcohesion.enunciate.modules.jaxrs.model.util.MediaType> consumesMt = resourceMethod.getConsumesMediaTypes();
      Set<String> consumes = new TreeSet<>();
      for (MediaType mediaType : consumesMt) {
        consumes.add(mediaType.getMediaType());
      }

      contextStack.push(ep.getDelegate());

      TypeMirror type = ep.getType();
      contextStack.push(resourceMethod);
      try {
        for (MediaTypeDefinitionModule mediaTypeModule : this.mediaTypeModules) {
          mediaTypeModule.addDataTypeDefinitions(type, consumes, contextStack);
        }
      }
      catch (RuntimeException e) {
        if (e.getClass().getName().endsWith("CompletionFailure")) {
          throw new CompletionFailureException(contextStack, e);
        }

        throw e;
      }
      finally {
        contextStack.pop();
      }
    }

    ResourceRepresentationMetadata outputPayload = resourceMethod.getRepresentationMetadata();
    if (outputPayload != null) {
      TypeMirror type = outputPayload.getDelegate();
      Set<com.webcohesion.enunciate.modules.jaxrs.model.util.MediaType> producesMt = resourceMethod.getProducesMediaTypes();
      Set<String> produces = new TreeSet<>();
      for (MediaType mediaType : producesMt) {
        produces.add(mediaType.getMediaType());
      }
      contextStack.push(resourceMethod);

      try {
        for (MediaTypeDefinitionModule mediaTypeModule : this.mediaTypeModules) {
          mediaTypeModule.addDataTypeDefinitions(type, produces, contextStack);
        }
      }
      catch (RuntimeException e) {
        if (e.getClass().getName().endsWith("CompletionFailure")) {
          throw new CompletionFailureException(contextStack, e);
        }

        throw e;
      }
      finally {
        contextStack.pop();
      }
    }

    List<? extends ResponseCode> statusCodes = resourceMethod.getStatusCodes();
    if (statusCodes != null) {
      for (ResponseCode statusCode : statusCodes) {
        TypeMirror type = statusCode.getType();
        if (type != null) {
          Set<com.webcohesion.enunciate.modules.jaxrs.model.util.MediaType> producesMt = resourceMethod.getProducesMediaTypes();
          Set<String> produces = new TreeSet<>();
          for (MediaType mediaType : producesMt) {
            produces.add(mediaType.getMediaType());
          }

          contextStack.push(resourceMethod);

          try {
            for (MediaTypeDefinitionModule mediaTypeModule : this.mediaTypeModules) {
              mediaTypeModule.addDataTypeDefinitions(type, produces, contextStack);
            }
          }
          catch (RuntimeException e) {
            if (e.getClass().getName().endsWith("CompletionFailure")) {
              throw new CompletionFailureException(contextStack, e);
            }

            throw e;
          }
          finally {
            contextStack.pop();
          }
        }
      }
    }
  }

  public EnunciateJaxrsContext.GroupingStrategy getGroupingStrategy() {
    String groupBy = this.config.getString("[@groupBy]", "class");
    if ("class".equals(groupBy)) {
      return EnunciateJaxrsContext.GroupingStrategy.resource_class;
    }
    else if ("path".equals(groupBy)) {
      return EnunciateJaxrsContext.GroupingStrategy.path;
    }
    else if ("annotation".equals(groupBy)) {
      return EnunciateJaxrsContext.GroupingStrategy.annotation;
    }
    else {
      throw new EnunciateException("Unknown grouping strategy: " + groupBy);
    }
  }

  @Override
  public boolean internal(ClassFile classFile) {
    String classname = classFile.getName();
    return classname.startsWith("org.glassfish.jersey")
      || classname.startsWith("com.sun.jersey")
      || classname.startsWith("org.jboss.resteasy")
      || classname.startsWith("org.apache.cxf");
  }

  @Override
  public boolean typeDetected(ClassFile classFile) {
    return annotationNames(classFile).anyMatch(classAnnotation -> 
       ((javax.ws.rs.Path.class.getName().equals(classAnnotation))
       || (jakarta.ws.rs.Path.class.getName().equals(classAnnotation))
       || (javax.ws.rs.ext.Provider.class.getName().equals(classAnnotation))
       || (jakarta.ws.rs.ext.Provider.class.getName().equals(classAnnotation))
       || (javax.ws.rs.ApplicationPath.class.getName().equals(classAnnotation))
       || (jakarta.ws.rs.ApplicationPath.class.getName().equals(classAnnotation))));
  }

  public class MediaTypeDependencySpec implements DependencySpec {

    @Override
    public boolean accept(EnunciateModule module) {
      if (module instanceof MediaTypeDefinitionModule) {
        MediaTypeDefinitionModule definitionModule = (MediaTypeDefinitionModule) module;
        mediaTypeModules.add(definitionModule);

        // suggest to the media type definition module that it should take a passive approach to detecting data types
        // because this module will be aggressively adding the data type definitions to it.
        definitionModule.setDefaultDataTypeDetectionStrategy(DataTypeDetectionStrategy.passive);
        return true;
      }

      return false;
    }

    @Override
    public boolean isFulfilled() {
      // this spec is always fulfilled.
      return true;
    }

    @Override
    public String toString() {
      return "media type definition modules";
    }
  }
}
