/*
 * Copyright 2009-2015 DigitalGlobe, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package org.mrgeo.data;

import org.mrgeo.core.MrGeoConstants;
import org.mrgeo.core.MrGeoProperties;

import java.util.Properties;

public class ProtectionLevelUtils
{
  /**
   * If the passed protection level is null or empty, then check to see
   * if MrGeo is configured to require a protection level. If so, return
   * the configured default protection level if it is non-null and non-empty.
   * Otherwise, throw an exception indicating that the required protection
   * level is missing.
   * 
   * If the passed protection level is null or empty, and MrGeo is configured
   * such that protection level is not required, then return a blank string.
   * 
   * @param protectionLevel
   * @return
   * @throws Exception
   */
  public static String getAndValidateProtectionLevel(final ProtectionLevelValidator validator,
      final String protectionLevel) throws Exception
  {
    String actualProtectionLevel = protectionLevel;
    if (actualProtectionLevel == null || actualProtectionLevel.isEmpty())
    {
      // No protection level was passed in, so we need to check to see
      // if it is required. If it is, then return the default protection
      // level if it is defined or throw an exception.
      Properties props = MrGeoProperties.getInstance();
      String protectionLevelRequired = props.getProperty(
          MrGeoConstants.MRGEO_PROTECTION_LEVEL_REQUIRED, "false").trim();
      if (protectionLevelRequired.equalsIgnoreCase("true"))
      {
        String protectionLevelDefault = props.getProperty(
            MrGeoConstants.MRGEO_PROTECTION_LEVEL_DEFAULT, "");
        if (protectionLevelDefault == null || protectionLevelDefault.isEmpty())
        {
          throw new Exception("Missing required protection level.");
        }
        actualProtectionLevel = protectionLevelDefault;
      }
      else
      {
        actualProtectionLevel = "";
      }
    }
    if (actualProtectionLevel != null && !actualProtectionLevel.isEmpty())
    {
      if (!validator.validateProtectionLevel(protectionLevel))
      {
        throw new Exception("Invalid visibility " + protectionLevel);
      }
    }
    return actualProtectionLevel;
  }
}
