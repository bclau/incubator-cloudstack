(function($, cloudStack, require) {
  var loadCSS = function(path) {
    var $link = $('<link>');

    $link.attr({
      rel: 'stylesheet',
      type: 'text/css',
      href: path
    });

    $('head').append($link);
  };

  var pluginAPI = {
    addSection: function(section) {
      cloudStack.sections[section.id] = section;
    },
    extend: function(obj) {
      $.extend(true, cloudStack, obj);
    }
  };
  
  cloudStack.sections.plugins = {
    title: 'label.plugins',
    show: cloudStack.uiCustom.plugins
  };

  // Load plugins
  $(cloudStack.plugins).map(function(index, pluginID) {
    var basePath = 'plugins/' + pluginID + '/';
    var pluginJS = basePath + pluginID + '.js';
    var configJS = basePath + 'config.js';
    var pluginCSS = basePath + pluginID + '.css';

    require([pluginJS], function() {
      require([configJS]);
      loadCSS(pluginCSS);

      // Execute plugin
      cloudStack.plugins[pluginID]({
        ui: pluginAPI
      });
    });

    // Load CSS
  });
}(jQuery, cloudStack, require));