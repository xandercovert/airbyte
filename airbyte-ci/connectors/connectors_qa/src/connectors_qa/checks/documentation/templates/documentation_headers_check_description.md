The user facing connector documentation should follow the guidelines defined in the [documentation standards](https://hackmd.io/Bz75cgATSbm7DjrAqgl4rw).

This check expects the following order of headers in the documentation:

````

{% for i in ordered_headers %}
  {{ i }}
{% endfor %}

````


List of not required headers, which can be not exist in the documentation and their strict check will be skipped:
{% for i in not_required_headers %}
  - {{ i }}
{% endfor %}