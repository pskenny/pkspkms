function hasProperty(file, prop_name)
    return file:get(prop_name) ~= nil
end


-- doesn't work for arrays
function hasPropertyValue(file, prop_name, value)
    local val = file:get(prop_name)
    if type(val) == "userdata" then
        return val:contains(value)
    end
    return val == value
end


function getPropertyValue(file, prop_name, default_value)
  local val = file:get(prop_name)
  if val == nil then
      return default_value
  end
  return val
end
