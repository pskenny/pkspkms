function hasProperty(file, prop_name)
    return file:get(prop_name) ~= nil
end


-- Scalar equality; list properties are matched by hasPropertyValueIn
function hasPropertyValue(file, prop_name, value)
    local val = file:get(prop_name)
    if type(val) == "userdata" then
        return val:contains(value)
    end
    return val == value
end

-- Substring match on scalar text; list membership (exact) for lists
function hasPropertyContaining(file, prop_name, value)
    local val = file:get(prop_name)
    if val == nil then
        return false
    end
    if type(val) == "userdata" then
        return val:contains(value)
    end
    return string.find(tostring(val), tostring(value), 1, true) ~= nil
end

function hasPropertyValueIn(file, prop_name, values)
    local val = file:get(prop_name)
    if type(val) == "userdata" then
        for i = 1, #values do
            if val:contains(values[i]) then
                return true
            end
        end
        return false
    end
    for i = 1, #values do
        if val == values[i] then
            return true
        end
    end
    return false
end


function getPropertyValue(file, prop_name, default_value)
  local val = file:get(prop_name)
  if val == nil then
      return default_value
  end
  return val
end

-- Numeric comparisons: missing or non-numeric property never matches
local function compareNumeric(file, prop_name, value, cmp)
    local val = file:get(prop_name)
    if val == nil then
        return false
    end
    local num = tonumber(val)
    if num == nil then
        return false
    end
    return cmp(num, value)
end

function hasPropertyValueGreaterThan(file, prop_name, value)
    return compareNumeric(file, prop_name, value, function(a, b) return a > b end)
end

function hasPropertyValueGreaterThanOrEqual(file, prop_name, value)
    return compareNumeric(file, prop_name, value, function(a, b) return a >= b end)
end

function hasPropertyValueLessThan(file, prop_name, value)
    return compareNumeric(file, prop_name, value, function(a, b) return a < b end)
end

function hasPropertyValueLessThanOrEqual(file, prop_name, value)
    return compareNumeric(file, prop_name, value, function(a, b) return a <= b end)
end

-- Obsidian file.* derivatives, derived from filePath
function fileField(file, field)
    local path = file:get("filePath")
    if path == nil then
        return ""
    end
    local name = string.match(path, "[^/]+$") or ""
    if field == "basename" then
        return string.match(name, "^(.*)%.[^%.]*$") or name
    end
    if field == "name" then
        return name
    end
    if field == "ext" then
        return string.match(name, "%.([^%.]*)$") or ""
    end
    return path
end

function fileFieldStartsWith(file, field, prefix)
    return string.sub(fileField(file, field), 1, #prefix) == prefix
end

function hasPropertyValueStartsWith(file, prop_name, prefix)
    local val = file:get(prop_name)
    if val == nil then
        return false
    end
    return string.sub(tostring(val), 1, #prefix) == prefix
end

-- Obsidian file.inFolder: filePath inside folder (prefix .. "/"); "" matches all
function fileInFolder(file, folder)
    local path = file:get("filePath")
    if path == nil then
        return false
    end
    if folder == "" then
        return true
    end
    if string.sub(folder, -1) == "/" then
        folder = string.sub(folder, 1, -2)
    end
    return string.sub(path, 1, #folder + 1) == folder .. "/"
end

function hasEmptyProperty(file, prop_name)
    local val = file:get(prop_name)
    if val == nil then
        return true
    end
    if type(val) == "string" then
        return val == ""
    end
    if type(val) == "userdata" then
        local ok, size = pcall(function() return val:size() end)
        if ok then
            return size == 0
        end
        local ok2, s = pcall(function() return val:toString() end)
        if ok2 then
            return s == ""
        end
    end
    return false
end

function hasPropertyContainingAll(file, prop_name, values)
    local val = file:get(prop_name)
    if type(val) ~= "userdata" then
        return false
    end
    for i = 1, #values do
        if not val:contains(values[i]) then
            return false
        end
    end
    return true
end
